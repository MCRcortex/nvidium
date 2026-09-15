package me.cortex.nvidium.lod;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.persist.PersistentMesh;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client voxel LOD. Near terrain stays Sodium/Nvidium full mesh.
 * Future (after the main client path is done):
 * - server with this mod streams voxel/LOD sections to the client
 * - offline generate from region files (Chunky-style) without walking
 */
public final class LodSystem {
    private final VoxelStore store;
    private final SectionManager sections;
    private final Long2ByteOpenHashMap gpuLod = new Long2ByteOpenHashMap();
    private final int[] onGpu = new int[LodLevels.MAX + 1];

    public LodSystem(Path persistentRoot, SectionManager sections) throws IOException {
        this.store = new VoxelStore(persistentRoot.resolve("voxels"));
        this.sections = sections;
        this.gpuLod.defaultReturnValue((byte) -1);
    }

    public void ingest(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        try {
            VoxelSection section = VoxelIngest.ingest(level, sectionX, sectionY, sectionZ);
            if (section == null || section.isEmpty()) {
                this.store.remove(SectionPos.asLong(sectionX, sectionY, sectionZ));
            } else {
                this.store.put(section);
            }
        } catch (Exception e) {
            Nvidium.LOGGER.error("Voxel ingest failed at {} {} {}", sectionX, sectionY, sectionZ, e);
        }
    }

    public PersistentMesh pickMesh(long sectionKey, PersistentMesh fullMesh, int camCX, int camCZ) {
        int needed = LodLevels.forSection(sectionKey, camCX, camCZ);
        if (needed == 0) {
            return fullMesh;
        }
        VoxelSection voxels = this.store.get(sectionKey);
        if (voxels == null) {
            return null;
        }
        return LodMeshBuilder.build(voxels, needed, faceNeighbors(sectionKey, needed));
    }

    private VoxelSection[] faceNeighbors(long sectionKey, int lod) {
        int x = SectionPos.x(sectionKey);
        int y = SectionPos.y(sectionKey);
        int z = SectionPos.z(sectionKey);
        return new VoxelSection[]{
                mipped(SectionPos.asLong(x + 1, y, z), lod),
                mipped(SectionPos.asLong(x - 1, y, z), lod),
                mipped(SectionPos.asLong(x, y + 1, z), lod),
                mipped(SectionPos.asLong(x, y - 1, z), lod),
                mipped(SectionPos.asLong(x, y, z + 1), lod),
                mipped(SectionPos.asLong(x, y, z - 1), lod)
        };
    }

    private VoxelSection mipped(long sectionKey, int lod) {
        VoxelSection section = this.store.get(sectionKey);
        if (section == null) {
            return null;
        }
        return section.mip(lod);
    }

    public byte lodOf(long sectionKey) {
        return this.gpuLod.get(sectionKey);
    }

    public void onUploaded(long sectionKey, byte lod) {
        byte prev = this.gpuLod.put(sectionKey, lod);
        if (prev >= 0) {
            this.onGpu[prev] = Math.max(0, this.onGpu[prev] - 1);
        }
        if (lod >= 0 && lod < this.onGpu.length) {
            this.onGpu[lod]++;
        }
    }

    public void onEvicted(long sectionKey) {
        byte prev = this.gpuLod.remove(sectionKey);
        if (prev >= 0) {
            this.onGpu[prev] = Math.max(0, this.onGpu[prev] - 1);
        }
    }

    public LongArrayList reconcileStale(int camCX, int camCZ, int budget) {
        LongArrayList stale = new LongArrayList();
        var iterator = this.gpuLod.long2ByteEntrySet().fastIterator();
        while (iterator.hasNext() && stale.size() < budget) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            byte have = entry.getByteValue();
            int needed = LodLevels.forSection(key, camCX, camCZ);
            if (have != needed) {
                stale.add(key);
            }
        }
        LongArrayList regions = new LongArrayList();
        for (long key : stale) {
            this.sections.evictSection(key);
            onEvicted(key);
            long regionKey = PersistentMesh.regionKey(key);
            if (!regions.contains(regionKey)) {
                regions.add(regionKey);
            }
        }
        return regions;
    }

    public void forgetRegion(long regionKey) {
        int x0 = SectionPos.x(regionKey) << 3;
        int y0 = SectionPos.y(regionKey) << 2;
        int z0 = SectionPos.z(regionKey) << 3;
        for (int x = x0; x < x0 + 8; x++) {
            for (int y = y0; y < y0 + 4; y++) {
                for (int z = z0; z < z0 + 8; z++) {
                    onEvicted(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    public String debugLine() {
        return "LOD start " + LodLevels.fullMeshChunks() + "c | gpu L0=" + this.onGpu[0]
                + " L1=" + this.onGpu[1] + " L2=" + this.onGpu[2]
                + " L3=" + this.onGpu[3] + " L4=" + this.onGpu[4] + ", voxels=" + this.store.storedCount();
    }

    public int voxelCount() {
        return this.store.storedCount();
    }

    public void close() {
        this.store.close();
    }
}
