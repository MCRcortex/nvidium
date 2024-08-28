package me.cortex.nvidium.managers;


import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.util.IdProvider;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

//8x4x8
public class RegionManager {
    public static final int MAX_TRANSFORMATION_SIZE_BITS = 10;
    public static final int MAX_TRANSFORMATION_COUNT = (1<<MAX_TRANSFORMATION_SIZE_BITS);

    private static final boolean SAFETY_CHECKS = Nvidium.IS_DEBUG;
    public static final int META_SIZE = 16;

    private static final int TOTAL_SECTION_META_SIZE = SectionManager.SECTION_SIZE * 256;

    private final IDeviceMappedBuffer regionBuffer;
    private final IDeviceMappedBuffer sectionBuffer;
    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;

    private final Long2IntOpenHashMap regionTransformationIdMapping = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap regionMap = new Long2IntOpenHashMap();
    private final IdProvider idProvider = new IdProvider();
    private final Region[] regions;

    private final ArrayDeque<Region> dirtyRegions = new ArrayDeque<>();

    private final Consumer<Integer> regionUploadCallback;

    public RegionManager(RenderDevice device, int maxRegions, int maxSections, UploadingBufferStream uploadStream, Consumer<Integer> regionUploaded) {
        this.regionMap.defaultReturnValue(-1);
        this.device = device;
        this.regionBuffer = device.createDeviceOnlyMappedBuffer((long) maxRegions * META_SIZE);
        this.sectionBuffer = device.createDeviceOnlyMappedBuffer((long) maxSections * SectionManager.SECTION_SIZE);
        this.uploadStream = uploadStream;
        this.regions = new Region[maxRegions];
        this.regionUploadCallback = regionUploaded;
    }

    public void delete() {
        this.regionBuffer.delete();
        this.sectionBuffer.delete();
    }

    //Commits all the pending region changes to the gpu
    public void commitChanges() {
        if (this.dirtyRegions.isEmpty())
            return;

        while (!this.dirtyRegions.isEmpty()) {
            var region = this.dirtyRegions.pop();
            region.isDirty = false;

            //If the region was removed, check if a new region took its place, if it has, no furthure action is needed
            // as the new region will override the old regions data
            if (region.isRemoved) {
                if (this.regions[region.id] == null) {
                    //There is no region that has replaced the old one at the id so we need to clear the region metadata
                    // to prevent the gpu from rendering arbitary data
                    long regionUpload = this.uploadStream.upload(this.regionBuffer, (long) region.id * META_SIZE, META_SIZE);
                    MemoryUtil.memSet(regionUpload, -1, META_SIZE);

                    long sectionUpload = this.uploadStream.upload(this.sectionBuffer,
                            (long) region.id * TOTAL_SECTION_META_SIZE,
                            TOTAL_SECTION_META_SIZE);
                    MemoryUtil.memSet(sectionUpload, 0, TOTAL_SECTION_META_SIZE);
                }
            } else {
                //It is just a normal region update
                long regionUpload = this.uploadStream.upload(this.regionBuffer, (long) region.id * META_SIZE, META_SIZE);
                this.setRegionMetadata(regionUpload, region);

                long sectionUpload = this.uploadStream.upload(this.sectionBuffer,
                        (long) region.id * TOTAL_SECTION_META_SIZE,
                        TOTAL_SECTION_META_SIZE);
                MemoryUtil.memCopy(region.sectionData, sectionUpload, TOTAL_SECTION_META_SIZE);

                this.regionUploadCallback.accept(region.id);
            }
        }
    }

    private void setRegionMetadata(long upload, Region region) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int lastIdx = 0;
        for (int i = 0; i < 256; i++) {
            if (region.pos2id[i] == -1) continue;//Skip over empty sections
            int x = i&7;
            int y = i>>>6;
            int z = (i>>>3)&7;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            lastIdx = i;
        }


        long size = (long)(maxY-minY)<<62 | (long)(maxX-minX)<<59 | (long)(maxZ-minZ)<<56;
        long count = (long)(lastIdx)<<48;
        long x = ((((long) region.rx <<3)+minX)&((1<<24)-1))<<24;
        long y = ((((long) region.ry <<2)+minY)&((1<<24)-1))<<0;//Can shrink y from needing to be 24 bits large if bits are needed for other data
        long z = ((((long) region.rz <<3)+minZ)&((1<<24)-1))<<(64-24);
        long transformationId = (((long)region.transformationId)<<(64-24-MAX_TRANSFORMATION_SIZE_BITS));
        MemoryUtil.memPutLong(upload, size|count|x|y);
        MemoryUtil.memPutLong(upload+8, z | transformationId);
    }

    public int getSectionRefId(int section) {
        var region = this.regions[section >>> 8];
        int id = region.pos2id[section&0xFF];
        if (id<0 || id>=256) {
            throw new IllegalStateException();
        }
        return id;
    }

    //Returns a pointer to where the section data can be read or updated
    // it has a lifetime of until any other function call to this class instance
    // will mark the region as dirty and needing an update
    public long setSectionData(int sectionId) {
        var region = this.regions[sectionId >>> 8];
        sectionId &= 0xFF;
        sectionId = region.pos2id[sectionId];
        if (sectionId<0 || sectionId>=256) {
            throw new IllegalStateException();
        }
        this.markDirty(region);
        return region.sectionData + (sectionId * SectionManager.SECTION_SIZE);
    }

    public void removeSection(int sectionId) {
        var region = this.regions[sectionId >>> 8];
        sectionId &= 0xFF;
        if (region == null) {
            throw new IllegalStateException("Region is null");
        }
        int sectionPos = sectionId;
        sectionId = region.pos2id[sectionId];

        //Set the metadata of the section to empty
        MemoryUtil.memSet(region.sectionData + (long) sectionId * SectionManager.SECTION_SIZE, 0, SectionManager.SECTION_SIZE);
        region.pos2id[sectionPos] = -1;
        region.id2pos[sectionId] = -1;
        region.verifyIntegrity();

        int endId = --region.count;
        //If the endId is not the sectionId we need to move whatever was at the end to the new position
        if (endId != sectionId) {
            int oldPos = region.id2pos[endId];
            if (oldPos == -1) {
                throw new IllegalStateException();
            }
            //Copy the data from the last element to the now vacant slot
            MemoryUtil.memCopy(region.sectionData + (long) endId * SectionManager.SECTION_SIZE, region.sectionData + (long) sectionId * SectionManager.SECTION_SIZE, SectionManager.SECTION_SIZE);
            MemoryUtil.memSet(region.sectionData + (long) endId * SectionManager.SECTION_SIZE, 0, SectionManager.SECTION_SIZE);

            if (region.id2pos[endId] == -1 || region.pos2id[oldPos] == -1) {
                throw new IllegalStateException();
            }

            region.id2pos[endId] = -1;
            region.pos2id[oldPos] = -1;
            region.id2pos[sectionId] = oldPos;
            region.pos2id[oldPos] = sectionId;


            //TODO:FIXME! the issue is that the internal tracking id needs to be updated, that is the id to the section
            // needs to change
            // to the region needs to change

            long ptr = region.sectionData + (long) sectionId * SectionManager.SECTION_SIZE + 4;
            int data = MemoryUtil.memGetInt(ptr);
            data &= ~(0xFF<<18);
            data |= sectionId<<18;
            MemoryUtil.memPutInt(ptr, data);

            region.verifyIntegrity();
        }



        if (region.count == 0) {
            //Remove the region and mark it as removed
            region.isRemoved = true;
            region.delete();
            this.regions[region.id] = null;
            this.idProvider.release(region.id);
            this.regionMap.remove(region.key);
        }


        this.markDirty(region);
        region.verifyIntegrity();
    }

    public int allocateSection(int sectionX, int sectionY, int sectionZ) {
        long regionKey = ChunkSectionPos.asLong(sectionX>>3, sectionY>>2, sectionZ>>3);
        int regionId = this.regionMap.computeIfAbsent(regionKey, k -> this.idProvider.provide());

        //The region doesnt exist so we must create a new one
        if (this.regions[regionId] == null) {
            this.regions[regionId] = new Region(regionId, sectionX>>3, sectionY>>2, sectionZ>>3);
            this.regions[regionId].transformationId = this.regionTransformationIdMapping.get(regionKey);
        }
        var region = this.regions[regionId];

        int sectionKey = ((sectionY & 3) << 6 | sectionX & 7 | (sectionZ & 7) << 3);
        int sectionId = region.count++;
        if (region.pos2id[sectionKey] != -1 || region.id2pos[sectionId] != -1) {
            throw new IllegalStateException("Section id not free!");
        }

        region.pos2id[sectionKey] = sectionId;
        region.id2pos[sectionId] = sectionKey;


        this.markDirty(region);

        region.verifyIntegrity();
        return sectionKey | (regionId << 8);
    }

    //Adds the region to the dirty list if it wasnt already in it
    private void markDirty(Region region) {
        if (region.isDirty)
            return;
        region.isDirty = true;
        this.dirtyRegions.add(region);
    }

    public int regionCount() {
        return this.regionMap.size();
    }

    public int maxRegions() {
        return this.regions.length;
    }

    public int maxRegionIndex() {
        return this.idProvider.maxIndex();
    }

    public boolean regionExists(int regionId) {
        return this.regions[regionId] != null;
    }

    public boolean isRegionVisible(Viewport frustum, int regionId) {
        var region = this.regions[regionId];
        if (region == null) {
            return false;
        } else {
            return frustum.isBoxVisible((region.rx<<7)+(1<<6),(region.ry<<6)+(1<<5), (region.rz<<7)+(1<<6), 1<<6, 1<<5, 1<<6);
        }
    }

    public int distance(int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        var region = this.regions[regionId];
        return  (Math.abs((region.rx<<3)+4-camChunkX)+
                Math.abs((region.ry<<2)+2-camChunkY)+
                Math.abs((region.rz<<3)+4-camChunkZ)+
                Math.abs((region.rx<<3)+3-camChunkX)+
                Math.abs((region.ry<<2)+1-camChunkY)+
                Math.abs((region.rz<<3)+3-camChunkZ))>>1;
    }

    public boolean withinSquare(int dist, int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        var region = this.regions[regionId];
        return  Math.abs((region.rx<<3)+4-camChunkX)<=dist &&
                Math.abs((region.ry<<2)+2-camChunkY)<=dist &&
                Math.abs((region.rz<<3)+4-camChunkZ)<=dist;
    }

    public boolean isRegionInACameraAxis(int regionId, double camX, double camY, double camZ) {
        var region = this.regions[regionId];
        //TODO: also account for region area instead of entire region
        return (region.rx<<7 <= camX && camX <= ((region.rx+1)<<7))||
               (region.ry<<6 <= camY && camY <= ((region.ry+1)<<6))||
               (region.rz<<7 <= camZ && camZ <= ((region.rz+1)<<7))
                ;
    }

    public long getRegionBufferAddress() {
        return this.regionBuffer.getDeviceAddress();
    }

    public long getSectionBufferAddress() {
        return this.sectionBuffer.getDeviceAddress();
    }

    public long regionIdToKey(int regionId) {
        if (this.regions[regionId] == null) {
            throw new IllegalStateException();
        }
        return this.regions[regionId].key;
    }

    public void setRegionTransformId(int x, int y, int z, int id) {
        if (id < 0 || id >= MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Transformation id out of bounds");
        }
        long regionKey = ChunkSectionPos.asLong(x, y, z);
        int oldId = this.regionTransformationIdMapping.put(regionKey, id);
        if (oldId != id) {
            //The region has a new id so need to set and propagate the data
            int regionId = this.regionMap.get(regionKey);
            if (regionId == -1) {
                //Region doesnt exist in memory so ignore it
                return;
            }
            var region = this.regions[regionId];
            region.transformationId = id;
            this.markDirty(region);
        }
    }

    private static class Region {
        private final int rx;
        private final int ry;
        private final int rz;
        private final long key;
        private final int id;

        public int transformationId = 0;

        private int count;
        private final int[] pos2id = new int[256];//Can be a short in all honesty
        private final int[] id2pos = new int[256];//Can be a short in all honesty

        private boolean isDirty;
        private boolean isRemoved;

        //Contains also all the metadata about the sections within, then on commit, upload the entire regions metadata
        // this should :tm: _drastically_ improve performance when mass edits are done to the world and the section metadata
        private final long sectionData = MemoryUtil.nmemAlloc(8*4*8*SectionManager.SECTION_SIZE);

        private Region(int id, int rx, int ry, int rz) {
            Arrays.fill(this.pos2id, -1);
            Arrays.fill(this.id2pos, -1);

            MemoryUtil.memSet(sectionData, 0, 256 * SectionManager.SECTION_SIZE);
            this.key = ChunkSectionPos.asLong(rx, ry, rz);
            this.id = id;

            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }

        public void delete() {
            MemoryUtil.nmemFree(this.sectionData);
        }

        public void verifyIntegrity() {
            if (!SAFETY_CHECKS) return;
            for (int i = 0; i < 256; i++) {
                if (this.id2pos[i] != -1 && this.pos2id[this.id2pos[i]] != i) {
                    throw new IllegalStateException();
                }
                if (this.pos2id[i] != -1 && this.id2pos[this.pos2id[i]] != i) {
                    throw new IllegalStateException();
                }
            }
        }
    }

    public void destroy() {
        this.sectionBuffer.delete();
        this.regionBuffer.delete();
    }
}
