package me.cortex.nvidium.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.persist.PersistentMesh;
import net.minecraft.core.SectionPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class VoxelStore {
    private static final int MAGIC = 0x4E56584C; // NVXL
    private static final int VERSION = 2;
    private static final int CACHE_LIMIT = 1024;

    private final Path root;
    private final ConcurrentHashMap<Long, VoxelSection> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, VoxelSection> dirty = new ConcurrentHashMap<>();
    private final LongOpenHashSet deleted = new LongOpenHashSet();
    private final LongOpenHashSet known = new LongOpenHashSet();
    private final Object indexLock = new Object();
    private final Object ioLock = new Object();
    private final Object deleteLock = new Object();
    private final ExecutorService io;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile boolean closed;
    private volatile int stored;

    public VoxelStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nvidium-voxel-io");
            t.setDaemon(true);
            return t;
        });
        scanExisting();
        this.stored = this.known.size();
        Nvidium.LOGGER.info("Opened voxel store at {} ({} sections)", root, this.stored);
    }

    public int storedCount() {
        return this.stored;
    }

    public void put(VoxelSection section) {
        if (this.closed || section == null) {
            return;
        }
        if (section.isEmpty()) {
            remove(section.sectionKey);
            return;
        }
        this.cache.put(section.sectionKey, section);
        this.dirty.put(section.sectionKey, section);
        synchronized (this.deleteLock) {
            this.deleted.remove(section.sectionKey);
        }
        synchronized (this.indexLock) {
            this.known.add(section.sectionKey);
            this.stored = this.known.size();
        }
        trimCache();
        scheduleFlush();
    }

    public void remove(long key) {
        if (this.closed) {
            return;
        }
        this.cache.remove(key);
        this.dirty.remove(key);
        synchronized (this.deleteLock) {
            this.deleted.add(key);
        }
        synchronized (this.indexLock) {
            this.known.remove(key);
            this.stored = this.known.size();
        }
        scheduleFlush();
    }

    public VoxelSection get(long key) {
        VoxelSection cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this.deleteLock) {
            if (this.deleted.contains(key)) {
                return null;
            }
        }
        VoxelSection dirtySection = this.dirty.get(key);
        if (dirtySection != null) {
            return dirtySection;
        }
        long region = PersistentMesh.regionKey(key);
        synchronized (this.ioLock) {
            Path file = regionFile(region);
            if (!Files.exists(file)) {
                return null;
            }
            try {
                for (VoxelSection section : readRegion(file)) {
                    this.cache.put(section.sectionKey, section);
                    if (section.sectionKey == key) {
                        cached = section;
                    }
                }
            } catch (IOException e) {
                Nvidium.LOGGER.error("Failed to read voxel region {}", file.getFileName(), e);
            }
        }
        trimCache();
        return cached;
    }

    public boolean has(long key) {
        synchronized (this.indexLock) {
            return this.known.contains(key);
        }
    }

    public void close() {
        this.closed = true;
        flushDirty();
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(15, TimeUnit.SECONDS)) {
                this.io.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void trimCache() {
        if (this.cache.size() <= CACHE_LIMIT) {
            return;
        }
        int extra = this.cache.size() - CACHE_LIMIT;
        var it = this.cache.keySet().iterator();
        while (extra-- > 0 && it.hasNext()) {
            Long key = it.next();
            if (!this.dirty.containsKey(key)) {
                it.remove();
            }
        }
    }

    private void scheduleFlush() {
        if (this.flushScheduled.compareAndSet(false, true)) {
            this.io.execute(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                this.flushScheduled.set(false);
                flushDirty();
                if (!this.dirty.isEmpty() || !this.deleted.isEmpty()) {
                    scheduleFlush();
                }
            });
        }
    }

    private void flushDirty() {
        Long2ObjectOpenHashMap<VoxelSection> snapshot = new Long2ObjectOpenHashMap<>();
        snapshot.putAll(this.dirty);
        snapshot.keySet().forEach(key -> this.dirty.remove(key, snapshot.get(key)));
        LongOpenHashSet removed = new LongOpenHashSet();
        synchronized (this.deleteLock) {
            removed.addAll(this.deleted);
            this.deleted.clear();
        }
        if (snapshot.isEmpty() && removed.isEmpty()) {
            return;
        }
        LongOpenHashSet regions = new LongOpenHashSet();
        snapshot.keySet().forEach(key -> regions.add(PersistentMesh.regionKey(key)));
        removed.forEach(key -> regions.add(PersistentMesh.regionKey(key)));
        synchronized (this.ioLock) {
            for (long region : regions) {
                try {
                    rewriteRegion(region, snapshot, removed);
                } catch (IOException e) {
                    Nvidium.LOGGER.error("Failed to write voxel region {}", region, e);
                }
            }
        }
    }

    private void rewriteRegion(long regionKey, Long2ObjectOpenHashMap<VoxelSection> snapshot, LongOpenHashSet removed) throws IOException {
        Long2ObjectOpenHashMap<VoxelSection> merged = new Long2ObjectOpenHashMap<>();
        Path file = regionFile(regionKey);
        if (Files.exists(file)) {
            for (VoxelSection section : readRegion(file)) {
                if (!removed.contains(section.sectionKey)) {
                    merged.put(section.sectionKey, section);
                }
            }
        }
        snapshot.forEach((key, section) -> {
            if (PersistentMesh.regionKey(key) == regionKey && !removed.contains(key)) {
                merged.put(key, section);
            }
        });
        if (merged.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(merged.size());
            for (VoxelSection section : merged.values()) {
                writeSection(out, section);
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<VoxelSection> readRegion(Path file) throws IOException {
        List<VoxelSection> list = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return list;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                list.add(readSection(in));
            }
        }
        return list;
    }

    private static void writeSection(DataOutputStream out, VoxelSection section) throws IOException {
        byte[] packed = pack(section);
        byte[] compressed = deflate(packed);
        out.writeLong(section.sectionKey);
        out.writeInt(packed.length);
        out.writeInt(compressed.length);
        out.write(compressed);
    }

    private static VoxelSection readSection(DataInputStream in) throws IOException {
        long key = in.readLong();
        int raw = in.readInt();
        int comp = in.readInt();
        byte[] compressed = in.readNBytes(comp);
        byte[] packed = inflate(compressed, raw);
        return unpack(key, packed);
    }

    private static byte[] pack(VoxelSection section) {
        byte[] out = new byte[VoxelSection.VOLUME * 5];
        int p = 0;
        for (int i = 0; i < VoxelSection.VOLUME; i++) {
            int v = section.voxels[i];
            out[p++] = (byte) (v >>> 24);
            out[p++] = (byte) (v >>> 16);
            out[p++] = (byte) (v >>> 8);
            out[p++] = (byte) v;
            out[p++] = section.light[i];
        }
        return out;
    }

    private static VoxelSection unpack(long key, byte[] packed) {
        int[] voxels = new int[VoxelSection.VOLUME];
        byte[] light = new byte[VoxelSection.VOLUME];
        int p = 0;
        for (int i = 0; i < VoxelSection.VOLUME; i++) {
            voxels[i] = ((packed[p] & 0xFF) << 24) | ((packed[p + 1] & 0xFF) << 16) | ((packed[p + 2] & 0xFF) << 8) | (packed[p + 3] & 0xFF);
            light[i] = packed[p + 4];
            p += 5;
        }
        return new VoxelSection(key, voxels, light);
    }

    private void scanExisting() throws IOException {
        if (!Files.isDirectory(this.root)) {
            return;
        }
        try (var stream = Files.list(this.root)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".nvxl")).forEach(path -> {
                try {
                    for (VoxelSection section : readRegion(path)) {
                        synchronized (this.indexLock) {
                            this.known.add(section.sectionKey);
                        }
                    }
                } catch (IOException e) {
                    Nvidium.LOGGER.error("Failed to scan voxel file {}", path.getFileName(), e);
                }
            });
        }
    }

    private Path regionFile(long regionKey) {
        return this.root.resolve("v." + SectionPos.x(regionKey) + "." + SectionPos.y(regionKey) + "." + SectionPos.z(regionKey) + ".nvxl");
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setInput(input);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length / 3);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            if (n > 0) {
                out.write(buf, 0, n);
            }
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] input, int rawLen) throws IOException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(input);
        byte[] output = new byte[rawLen];
        try {
            int got = 0;
            while (got < rawLen && !inflater.finished()) {
                int n = inflater.inflate(output, got, rawLen - got);
                if (n == 0) {
                    break;
                }
                got += n;
            }
            if (got != rawLen) {
                throw new IOException("Voxel inflate size mismatch");
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return output;
    }
}
