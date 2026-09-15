package me.cortex.nvidium.persist;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.nvidium.Nvidium;
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

public final class PersistentSectionStore {
    private static final int MAGIC = 0x4E565053; // NVPS
    private static final int INDEX_MAGIC = 0x4E565049; // NVPI
    private static final int VERSION = 1;

    private final Path root;
    private final int stride;
    private final ConcurrentHashMap<Long, PersistentMesh> dirty = new ConcurrentHashMap<>();
    private final LongOpenHashSet deleted = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<long[]> regionSections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet knownKeys = new LongOpenHashSet();
    private final Object indexLock = new Object();
    private final Object ioLock = new Object();
    private final Object deleteLock = new Object();
    private final ExecutorService io;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile boolean closed;
    private volatile long diskBytes;
    private volatile int writesCompleted;

    public PersistentSectionStore(Path root, int stride) throws IOException {
        this.root = root;
        this.stride = stride;
        Files.createDirectories(root);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "nvidium-persist-io");
            thread.setDaemon(true);
            return thread;
        });
        loadIndex();
        this.diskBytes = computeDiskBytes();
        Nvidium.LOGGER.info("Opened persistent mesh store at {} ({} sections)", root, this.knownKeys.size());
    }

    public int storedCount() {
        synchronized (this.indexLock) {
            return this.knownKeys.size();
        }
    }

    public long diskBytes() {
        return this.diskBytes;
    }

    public int pendingWrites() {
        return this.dirty.size();
    }

    public int writesCompleted() {
        return this.writesCompleted;
    }

    public LongSet regionKeys() {
        synchronized (this.indexLock) {
            return new LongOpenHashSet(this.regionSections.keySet());
        }
    }

    public long[] sectionsInRegion(long regionKey) {
        synchronized (this.indexLock) {
            long[] keys = this.regionSections.get(regionKey);
            return keys == null ? new long[0] : keys.clone();
        }
    }

    public void put(PersistentMesh mesh) {
        if (this.closed || mesh == null || mesh.quads <= 0) {
            return;
        }
        synchronized (this.deleteLock) {
            this.deleted.remove(mesh.sectionKey);
        }
        this.dirty.put(mesh.sectionKey, mesh);
        synchronized (this.indexLock) {
            this.knownKeys.add(mesh.sectionKey);
        }
        scheduleFlush();
    }

    public void remove(long sectionKey) {
        if (this.closed) {
            return;
        }
        this.dirty.remove(sectionKey);
        synchronized (this.deleteLock) {
            this.deleted.add(sectionKey);
        }
        synchronized (this.indexLock) {
            this.knownKeys.remove(sectionKey);
        }
        scheduleFlush();
    }

    public List<PersistentMesh> loadRegion(long regionKey) {
        List<PersistentMesh> meshes = new ArrayList<>();
        LongOpenHashSet skip = new LongOpenHashSet();
        synchronized (this.deleteLock) {
            skip.addAll(this.deleted);
        }
        for (var entry : this.dirty.entrySet()) {
            if (PersistentMesh.regionKey(entry.getKey()) == regionKey && !skip.contains(entry.getKey())) {
                meshes.add(entry.getValue());
                skip.add(entry.getKey());
            }
        }
        synchronized (this.ioLock) {
            Path file = regionFile(regionKey);
            if (Files.exists(file)) {
                try {
                    for (PersistentMesh mesh : readRegionFile(file)) {
                        if (!skip.contains(mesh.sectionKey)) {
                            meshes.add(mesh);
                        }
                    }
                } catch (IOException e) {
                    Nvidium.LOGGER.error("Failed to read persistent region {}", file.getFileName(), e);
                }
            }
        }
        return meshes;
    }

    public void flush() {
        flushDirty();
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

    private void scheduleFlush() {
        if (this.closed) {
            return;
        }
        if (this.flushScheduled.compareAndSet(false, true)) {
            this.io.execute(() -> {
                try {
                    Thread.sleep(400);
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
        Long2ObjectOpenHashMap<PersistentMesh> snapshot = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet removed = new LongOpenHashSet();
        snapshot.putAll(this.dirty);
        for (long key : snapshot.keySet()) {
            this.dirty.remove(key, snapshot.get(key));
        }
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
            for (long regionKey : regions) {
                try {
                    rewriteRegion(regionKey, snapshot, removed);
                } catch (IOException e) {
                    Nvidium.LOGGER.error("Failed to write persistent region {}", regionKey, e);
                    snapshot.forEach(this.dirty::putIfAbsent);
                    synchronized (this.deleteLock) {
                        this.deleted.addAll(removed);
                    }
                    return;
                }
            }
            try {
                writeIndex();
                this.diskBytes = computeDiskBytes();
            } catch (IOException e) {
                Nvidium.LOGGER.error("Failed to write persistent index", e);
            }
        }
        this.writesCompleted += snapshot.size() + removed.size();
    }

    private void rewriteRegion(long regionKey, Long2ObjectOpenHashMap<PersistentMesh> snapshot, LongOpenHashSet removed) throws IOException {
        Long2ObjectOpenHashMap<PersistentMesh> merged = new Long2ObjectOpenHashMap<>();
        Path file = regionFile(regionKey);
        if (Files.exists(file)) {
            for (PersistentMesh mesh : readRegionFile(file)) {
                if (!removed.contains(mesh.sectionKey)) {
                    merged.put(mesh.sectionKey, mesh);
                }
            }
        }
        snapshot.forEach((key, mesh) -> {
            if (PersistentMesh.regionKey(key) == regionKey && !removed.contains(key)) {
                merged.put(key, mesh);
            }
        });

        if (merged.isEmpty()) {
            Files.deleteIfExists(file);
            synchronized (this.indexLock) {
                this.regionSections.remove(regionKey);
            }
            return;
        }

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(this.stride);
            out.writeInt(merged.size());
            for (PersistentMesh mesh : merged.values()) {
                writeMesh(out, mesh);
            }
        }
        atomicReplace(tmp, file);

        long[] keys = merged.keySet().toLongArray();
        synchronized (this.indexLock) {
            this.regionSections.put(regionKey, keys);
            for (long key : keys) {
                this.knownKeys.add(key);
            }
        }
    }

    private List<PersistentMesh> readRegionFile(Path file) throws IOException {
        List<PersistentMesh> meshes = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            int magic = in.readInt();
            int version = in.readInt();
            int fileStride = in.readInt();
            if (magic != MAGIC || version != VERSION || fileStride != this.stride) {
                Nvidium.LOGGER.warn("Ignoring incompatible persistent file {}", file.getFileName());
                return meshes;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                meshes.add(readMesh(in));
            }
        }
        return meshes;
    }

    private void writeMesh(DataOutputStream out, PersistentMesh mesh) throws IOException {
        byte[] compressed = deflate(mesh.geometry);
        out.writeLong(mesh.sectionKey);
        out.writeInt(mesh.quads);
        out.writeInt(pack3(mesh.minX, mesh.minY, mesh.minZ));
        out.writeInt(pack3(mesh.sizeX, mesh.sizeY, mesh.sizeZ));
        for (int i = 0; i < 8; i++) {
            out.writeInt(i < mesh.offsets.length ? mesh.offsets[i] : 0);
        }
        out.writeInt(mesh.geometry.length);
        out.writeInt(compressed.length);
        out.write(compressed);
    }

    private PersistentMesh readMesh(DataInputStream in) throws IOException {
        long key = in.readLong();
        int quads = in.readInt();
        int minPacked = in.readInt();
        int sizePacked = in.readInt();
        int[] offsets = new int[8];
        for (int i = 0; i < 8; i++) {
            offsets[i] = in.readInt();
        }
        int rawLen = in.readInt();
        int compLen = in.readInt();
        byte[] compressed = in.readNBytes(compLen);
        if (compressed.length != compLen) {
            throw new IOException("Truncated mesh payload for " + key);
        }
        byte[] geometry = inflate(compressed, rawLen);
        return new PersistentMesh(
                key,
                quads,
                offsets,
                unpackX(minPacked), unpackY(minPacked), unpackZ(minPacked),
                unpackX(sizePacked), unpackY(sizePacked), unpackZ(sizePacked),
                geometry
        );
    }

    private void loadIndex() throws IOException {
        Path index = indexFile();
        if (Files.exists(index)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
                if (in.readInt() != INDEX_MAGIC || in.readInt() != VERSION || in.readInt() != this.stride) {
                    Nvidium.LOGGER.warn("Persistent index incompatible, rebuilding");
                    rebuildIndexFromFiles();
                    return;
                }
                int regions = in.readInt();
                synchronized (this.indexLock) {
                    for (int i = 0; i < regions; i++) {
                        long regionKey = in.readLong();
                        int count = in.readInt();
                        long[] keys = new long[count];
                        for (int j = 0; j < count; j++) {
                            keys[j] = in.readLong();
                            this.knownKeys.add(keys[j]);
                        }
                        this.regionSections.put(regionKey, keys);
                    }
                }
                return;
            } catch (IOException e) {
                Nvidium.LOGGER.warn("Failed to read persistent index, rebuilding", e);
            }
        }
        rebuildIndexFromFiles();
    }

    private void rebuildIndexFromFiles() throws IOException {
        synchronized (this.indexLock) {
            this.regionSections.clear();
            this.knownKeys.clear();
        }
        if (!Files.isDirectory(this.root)) {
            return;
        }
        try (var stream = Files.list(this.root)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".nvps")).forEach(path -> {
                try {
                    List<PersistentMesh> meshes = readRegionFile(path);
                    if (meshes.isEmpty()) {
                        return;
                    }
                    long regionKey = PersistentMesh.regionKey(meshes.getFirst().sectionKey);
                    long[] keys = new long[meshes.size()];
                    for (int i = 0; i < meshes.size(); i++) {
                        keys[i] = meshes.get(i).sectionKey;
                    }
                    synchronized (this.indexLock) {
                        this.regionSections.put(regionKey, keys);
                        for (long key : keys) {
                            this.knownKeys.add(key);
                        }
                    }
                } catch (IOException e) {
                    Nvidium.LOGGER.error("Failed to scan {}", path.getFileName(), e);
                }
            });
        }
        writeIndex();
    }

    private void writeIndex() throws IOException {
        Path index = indexFile();
        Path tmp = index.resolveSibling("index.nvpi.tmp");
        synchronized (this.indexLock) {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(INDEX_MAGIC);
                out.writeInt(VERSION);
                out.writeInt(this.stride);
                out.writeInt(this.regionSections.size());
                this.regionSections.long2ObjectEntrySet().fastForEach(entry -> {
                    try {
                        out.writeLong(entry.getLongKey());
                        long[] keys = entry.getValue();
                        out.writeInt(keys.length);
                        for (long key : keys) {
                            out.writeLong(key);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw e;
            }
        }
        atomicReplace(tmp, index);
    }

    private Path regionFile(long regionKey) {
        int x = SectionPos.x(regionKey);
        int y = SectionPos.y(regionKey);
        int z = SectionPos.z(regionKey);
        return this.root.resolve("r." + x + "." + y + "." + z + ".nvps");
    }

    private Path indexFile() {
        return this.root.resolve("index.nvpi");
    }

    private long computeDiskBytes() {
        try (var stream = Files.list(this.root)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void atomicReplace(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int pack3(byte x, byte y, byte z) {
        return (x & 0xFF) | ((y & 0xFF) << 8) | ((z & 0xFF) << 16);
    }

    private static byte unpackX(int packed) {
        return (byte) packed;
    }

    private static byte unpackY(int packed) {
        return (byte) (packed >> 8);
    }

    private static byte unpackZ(int packed) {
        return (byte) (packed >> 16);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setInput(input);
        deflater.finish();
        byte[] buf = new byte[Math.max(1024, input.length / 4)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length / 2);
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
                throw new IOException("Inflated " + got + " bytes, expected " + rawLen);
            }
        } catch (DataFormatException e) {
            throw new IOException("Failed to inflate mesh", e);
        } finally {
            inflater.end();
        }
        return output;
    }
}
