package me.cortex.nvidium.persist;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.lod.LodSystem;
import me.cortex.nvidium.managers.SectionManager;
import net.minecraft.core.SectionPos;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistentMeshLoader {
    private static final int READY_CAPACITY = 512;
    private static final int UPLOADS_PER_FRAME = 48;
    private static final int REQUESTS_PER_TICK = 8;

    private final PersistentSectionStore store;
    private final SectionManager sectionManager;
    private final LodSystem lodSystem;
    private volatile int camCX, camCY, camCZ;
    private final BlockingQueue<Long> regionRequests = new ArrayBlockingQueue<>(256);
    private final BlockingQueue<PersistentMesh> ready = new ArrayBlockingQueue<>(READY_CAPACITY);
    private final LongOpenHashSet requestedRegions = new LongOpenHashSet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger loadedFromDisk = new AtomicInteger();
    private final AtomicInteger uploadQueueSize = new AtomicInteger();
    private final Thread thread;
    private int tick;

    public PersistentMeshLoader(PersistentSectionStore store, SectionManager sectionManager, LodSystem lodSystem) {
        this.store = store;
        this.sectionManager = sectionManager;
        this.lodSystem = lodSystem;
        this.thread = new Thread(this::runLoader, "nvidium-persist-load");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void tick(double camX, double camY, double camZ, int usedMb, int maxMb) {
        this.camCX = SectionPos.blockToSectionCoord((int) Math.floor(camX));
        this.camCY = SectionPos.blockToSectionCoord((int) Math.floor(camY));
        this.camCZ = SectionPos.blockToSectionCoord((int) Math.floor(camZ));
        drainUploads(usedMb, maxMb);
        if (this.lodSystem != null && (this.tick % 10) == 0) {
            int upgraded = this.lodSystem.upgradeStale(this.camCX, this.camCY, this.camCZ, 8);
            if (upgraded > 0) {
                synchronized (this.requestedRegions) {
                    this.requestedRegions.clear();
                }
            }
        }
        if ((this.tick++ % 5) == 0) {
            enqueueNearbyRegions(camX, camY, camZ, usedMb, maxMb);
        }
    }

    public void allowReload(long regionKey) {
        synchronized (this.requestedRegions) {
            this.requestedRegions.remove(regionKey);
        }
    }

    public int loadedFromDisk() {
        return this.loadedFromDisk.get();
    }

    public int uploadQueue() {
        return this.uploadQueueSize.get();
    }

    public void close() {
        this.running.set(false);
        this.thread.interrupt();
        try {
            this.thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.ready.clear();
        this.regionRequests.clear();
    }

    private void drainUploads(int usedMb, int maxMb) {
        int budget = UPLOADS_PER_FRAME;
        while (budget-- > 0) {
            if (usedMb > maxMb - 150) {
                break;
            }
            PersistentMesh mesh = this.ready.poll();
            if (mesh == null) {
                break;
            }
            this.uploadQueueSize.decrementAndGet();
            if (this.sectionManager.hasSection(mesh.sectionKey)) {
                continue;
            }
            if (this.sectionManager.uploadPersistedMesh(mesh)) {
                this.loadedFromDisk.incrementAndGet();
                if (this.lodSystem != null) {
                    this.lodSystem.onUploaded(mesh.sectionKey, mesh.lod);
                }
            }
        }
    }

    private void enqueueNearbyRegions(double camX, double camY, double camZ, int usedMb, int maxMb) {
        if (usedMb > maxMb - 200) {
            return;
        }
        if (this.regionRequests.remainingCapacity() < 4 || this.ready.remainingCapacity() < 64) {
            return;
        }

        int camCX = SectionPos.blockToSectionCoord((int) Math.floor(camX));
        int camCY = SectionPos.blockToSectionCoord((int) Math.floor(camY));
        int camCZ = SectionPos.blockToSectionCoord((int) Math.floor(camZ));
        int keep = Nvidium.config.keepUntilVramLimit() ? 512 : Nvidium.config.gpuKeepChunks();

        LongArrayList candidates = new LongArrayList();
        for (long regionKey : this.store.regionKeys()) {
            if (!withinKeep(regionKey, camCX, camCY, camCZ, keep)) {
                continue;
            }
            synchronized (this.requestedRegions) {
                if (this.requestedRegions.contains(regionKey)) {
                    continue;
                }
            }
            if (regionFullyOnGpu(regionKey)) {
                synchronized (this.requestedRegions) {
                    this.requestedRegions.add(regionKey);
                }
                continue;
            }
            candidates.add(regionKey);
        }

        candidates.sort(Comparator.comparingInt(rk -> regionDistance(rk, camCX, camCY, camCZ)));
        int issued = 0;
        for (long regionKey : candidates) {
            if (issued >= REQUESTS_PER_TICK) {
                break;
            }
            if (this.regionRequests.offer(regionKey)) {
                synchronized (this.requestedRegions) {
                    this.requestedRegions.add(regionKey);
                }
                issued++;
            } else {
                break;
            }
        }
    }

    private boolean regionFullyOnGpu(long regionKey) {
        long[] keys = this.store.sectionsInRegion(regionKey);
        if (keys.length == 0) {
            return true;
        }
        for (long key : keys) {
            if (!this.sectionManager.hasSection(key)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinKeep(long regionKey, int camCX, int camCY, int camCZ, int keep) {
        int rx = (SectionPos.x(regionKey) << 3) + 4;
        int ry = (SectionPos.y(regionKey) << 2) + 2;
        int rz = (SectionPos.z(regionKey) << 3) + 4;
        return Math.abs(rx - camCX) <= keep && Math.abs(ry - camCY) <= keep && Math.abs(rz - camCZ) <= keep;
    }

    private static int regionDistance(long regionKey, int camCX, int camCY, int camCZ) {
        int rx = (SectionPos.x(regionKey) << 3) + 4;
        int ry = (SectionPos.y(regionKey) << 2) + 2;
        int rz = (SectionPos.z(regionKey) << 3) + 4;
        return Math.abs(rx - camCX) + Math.abs(ry - camCY) + Math.abs(rz - camCZ);
    }

    private void runLoader() {
        while (this.running.get()) {
            try {
                Long regionKey = this.regionRequests.poll(200, TimeUnit.MILLISECONDS);
                if (regionKey == null) {
                    continue;
                }
                List<PersistentMesh> meshes = this.store.loadRegion(regionKey);
                for (PersistentMesh mesh : meshes) {
                    if (!this.running.get()) {
                        return;
                    }
                    PersistentMesh chosen = mesh;
                    if (this.lodSystem != null) {
                        chosen = this.lodSystem.pickMesh(mesh.sectionKey, mesh, this.camCX, this.camCY, this.camCZ);
                    }
                    if (chosen == null) {
                        continue;
                    }
                    this.ready.put(chosen);
                    this.uploadQueueSize.incrementAndGet();
                }
            } catch (InterruptedException e) {
                if (!this.running.get()) {
                    return;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Nvidium.LOGGER.error("Persistent mesh loader failed", e);
            }
        }
    }
}
