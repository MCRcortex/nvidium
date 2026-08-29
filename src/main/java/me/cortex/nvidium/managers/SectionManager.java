package me.cortex.nvidium.managers;

import it.unimi.dsi.fastutil.longs.*;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.util.BufferArena;
import me.cortex.nvidium.util.SegmentedManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static me.cortex.nvidium.Nvidium.LOGGER;

public class SectionManager {
    public static final int SECTION_SIZE = 32 + 16;

    //Sections should be grouped and batched into sizes of the count of sections in a region
    private final RegionManager regionManager;

    private final Long2IntOpenHashMap section2id = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2terrain = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2index = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap section2idxDataCount = new Long2IntOpenHashMap();

    private final int quadVertexSize;
    public final UploadingBufferStream uploadStream;
    public final BufferArena terrainAreana;

    private final Long2ObjectOpenHashMap<int[]> translucencyQuadCounts = new Long2ObjectOpenHashMap<int[]>();

    private final RenderDevice device;

    private final LongSet hiddenSectionKeys = new LongOpenHashSet();

    public SectionManager(RenderDevice device, long fallbackMemorySize, UploadingBufferStream uploadStream, int quadVertexSize, NvidiumWorldRenderer worldRenderer) {
        int maxRegions = 50_000;

        this.device = device;
        this.uploadStream = uploadStream;

        this.quadVertexSize = quadVertexSize;

        this.terrainAreana = new BufferArena(device, fallbackMemorySize, quadVertexSize);
        this.regionManager = new RegionManager(device, maxRegions, maxRegions * 200, uploadStream, worldRenderer::enqueueRegionSort);

        this.section2id.defaultReturnValue(-1);
        this.section2terrain.defaultReturnValue(-1);
        this.section2index.defaultReturnValue(-1);

        this.translucencyQuadCounts.defaultReturnValue(null);
    }

    public void uploadIndexBuffer(IntBuffer indexBuffer, int[] quadCountData, long upload) {
        int quadOffset = 0;
        for (var facing : ModelQuadFacing.values()) {
            for (int i = 0; i < quadCountData[facing.ordinal()]; i++) {
                // We only need 1 index out of 6 because we are working with quad indexes, also /4 because we have 4 vertices per quad
                int idx = (indexBuffer.get((quadOffset + i) * 6) / 4) + quadOffset;
                MemoryUtil.memPutInt(upload + (long)(quadOffset + i) * 4, idx);
            }
            quadOffset += quadCountData[facing.ordinal()];
        }
    }

    public void uploadChunkSort(ChunkSortOutput sortOutput) {
        if (sortOutput.getSorter() == null) {
            return;
        }
        NativeBuffer indexBuffer = sortOutput.getSorter().getIndexBuffer();
        if (indexBuffer == null) {
            return;
        }

        RenderSection section = sortOutput.section;
        long sectionKey = SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        var quadCountData = this.translucencyQuadCounts.get(sectionKey);
        if (quadCountData == null) { // early exist if we don't have a section
            return;
        }

        // Quick dirty integrity check to prevent race condition crash because translucencyQuadCounts can be overridden by an already reprocessed chunk
        if (quadCountData[7] * 6 * 4 != indexBuffer.getLength()) {
            LOGGER.error("ChunkSortOutput integrity check failed at {} {} {}, aborting (totalQuads={};indexBuffer={})",
                    section.getChunkX(), section.getChunkY(), section.getChunkZ(), quadCountData[7], indexBuffer.getLength() / 24);
            return;
        }

        int indexDataAddress;
        {
            // We are hijacking terrain buffer to store indices so we need to /6 because we need only one index per quad & scale our storage on terrain storage
            var idxBufferLength = ((indexBuffer.getLength() / 6) + (quadVertexSize - 1)) / quadVertexSize;
            IntBuffer idxBuffer = indexBuffer.getDirectBuffer().asIntBuffer();

            indexDataAddress = this.section2index.get(sectionKey);
            if (indexDataAddress != -1 && !this.terrainAreana.canReuse(indexDataAddress, idxBufferLength)) {
                this.section2index.remove(sectionKey);
                this.section2idxDataCount.remove(sectionKey);
                this.terrainAreana.free(indexDataAddress);
                indexDataAddress = -1;
            }

            if (indexDataAddress == -1) {
                indexDataAddress = this.terrainAreana.allocQuads(idxBufferLength);
            }

            this.section2index.put(sectionKey, indexDataAddress);
            this.section2idxDataCount.put(sectionKey, indexBuffer.getLength() / 24);

            long upload = terrainAreana.upload(uploadStream, indexDataAddress);
            uploadIndexBuffer(idxBuffer, quadCountData, upload);
        }

        int sectionIdx = this.section2id.get(sectionKey);
        if (sectionIdx == -1) {
            // We shouldn't get there, section should be created by ChunkBuildOutput
            LOGGER.error("Got translucency data but no section found for {}", sectionKey);
            return;
        }

        long metadata = regionManager.setSectionData(sectionIdx);
        metadata += 32 + 4; // Go to translucency data offset
        MemoryUtil.memPutInt(metadata, indexDataAddress * quadVertexSize); // Scale address since we have ints instead of ChunkVertexFormat
    }

    public void uploadChunkBuildResult(ChunkBuildOutput result) {
        var output = ((IRepackagedResult)result).getOutput();

        RenderSection section = result.section;
        long sectionKey = SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());

        if (output == null || output.quads() == 0) {
            deleteSection(sectionKey);
            return;
        }

        // We need to store quadCount per ModelFacing to pad translucency sorting data
        int translucentQuadCount = 0;
        var translucentData = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            int[] quadOffsets = new int[8];
            for (int i = 0; i < ModelQuadFacing.COUNT; i++) {
                var count = translucentData.getVertexSegments()[i * 2];
                var facing = translucentData.getVertexSegments()[i * 2 + 1];
                if (count > 0) {
                    quadOffsets[facing] = count / 4;
                    quadOffsets[7] += count / 4;
                }
            }
            translucencyQuadCounts.put(sectionKey, quadOffsets);
            translucentQuadCount = quadOffsets[7];
        }

        int terrainAddress;
        {
            //Attempt to reuse the same memory
            terrainAddress = this.section2terrain.get(sectionKey);
            if (terrainAddress != -1 && !this.terrainAreana.canReuse(terrainAddress, output.quads())) {
                this.section2terrain.remove(sectionKey);
                this.terrainAreana.free(terrainAddress);
                terrainAddress = -1;
            }

            if (terrainAddress == -1) {
                terrainAddress = this.terrainAreana.allocQuads(output.quads());
            }

            if (terrainAddress == SegmentedManager.SIZE_LIMIT) {
                Nvidium.LOGGER.error("Terrain arena critically out of memory, expect issues with chunks!! " +
                        " quad_used: " + this.terrainAreana.getUsedMB() +
                        " physically used: " + this.terrainAreana.getMemoryUsed() +
                        " limit: " + ((INvidiumWorldRendererGetter)(SodiumWorldRenderer.instance())).getRenderer().getMaxGeometryMemory());

                deleteSection(sectionKey);
                return;
            }

            this.section2terrain.put(sectionKey, terrainAddress);

            long geometryUpload = terrainAreana.upload(uploadStream, terrainAddress);
            MemoryUtil.memCopy(MemoryUtil.memAddress(output.geometry().getDirectBuffer()), geometryUpload, output.geometry().getLength());
        }



        //Get the section id or allocate a new instance for it
        int sectionIdx = this.section2id.computeIfAbsent(
                sectionKey,
                key -> this.regionManager.allocateSection(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
        );


        long metadata = regionManager.setSectionData(sectionIdx);
        boolean hideSectionBitSet = this.hiddenSectionKeys.contains(sectionKey);
        Vector3i min  = output.min();
        Vector3i size = output.size();


        //bits 18->26 taken by section id (used for translucency sorting/rendering)
        // 26->32 is free
        int px = section.getChunkX()<<8 | size.x<<4 | min.x;
        int py = (section.getChunkY()&0x1FF)<<8 | size.y<<4 | min.y | (hideSectionBitSet?1<<17:0) | ((regionManager.getSectionRefId(sectionIdx))<<18);
        int pz = section.getChunkZ()<<8 | size.z<<4 | min.z;
        int pw = terrainAddress;
        new Vector4i(px, py, pz, pw).getToAddress(metadata);
        metadata += 4*4;

        //Write the geometry offsets, packed into ints
        for (int i = 0; i < 4; i++) {
            int geo = (output.offsets()[i*2]&0xffff)|(output.offsets()[i*2+1]&0xffff)<<16;
            MemoryUtil.memPutInt(metadata, geo);
            metadata += 4;
        }
        // Put translucent quad count separately as int to prevent overflow, will do trick for now
        System.out.println(output.offsets()[7]);
        MemoryUtil.memPutInt(metadata, output.offsets()[7]);
        metadata += 4;

        // Reinject or free index data
        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            if (result.containsNewIndexData()) {
                MemoryUtil.memPutInt(metadata, -1);

                int idxIndex = this.section2index.remove(sectionKey);
                if (idxIndex != -1) {
                    this.terrainAreana.free(idxIndex);
                }
            } else {
                int trIdx = this.section2index.get(sectionKey);

                if (this.section2idxDataCount.get(sectionKey) != translucentQuadCount) {
                    // Count don't match so discard translucent index data and free
                    MemoryUtil.memPutInt(metadata, -1);
                    int idxIndex = this.section2index.remove(sectionKey);
                    if (idxIndex != -1) {
                        this.terrainAreana.free(idxIndex);
                    }
                } else {
                    // Not 100% sure if the old data is still good for our new chunkbuild but if count is good at least we shouldn't crash
                    // Don't forget to scale and don't scale -1 (no data)
                    MemoryUtil.memPutInt(metadata, (trIdx != -1 ? trIdx * quadVertexSize : -1));
                }
            }
        }
    }

    public void setHideBit(int x, int y, int z, boolean hide) {
        long sectionKey = SectionPos.asLong(x, y, z);

        if (hide) {
            //Do a fast return if it was already hidden
            if (!this.hiddenSectionKeys.add(sectionKey)) {
                return;
            }
        } else {
            //Do a fast return if the section was not hidden
            if (!this.hiddenSectionKeys.remove(sectionKey)) {
                return;
            }
        }

        int sectionId = this.section2id.get(sectionKey);
        //Only update the section if it is loaded
        if (sectionId != -1) {
            long metadata = this.regionManager.setSectionData(sectionId);
            MemoryUtil.memPutInt(metadata + 4, (MemoryUtil.memGetInt(metadata + 4)&~(1<<17))| (hide?1:0)<<17);
        }
    }

    public void deleteSection(RenderSection section) {
        deleteSection(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }

    private void deleteSection(long sectionKey) {
        int sectionIdx = this.section2id.remove(sectionKey);
        if (sectionIdx != -1) {
            int terrainIndex = this.section2terrain.remove(sectionKey);
            if (terrainIndex != -1) {
                this.terrainAreana.free(terrainIndex);
            }
            this.section2idxDataCount.remove(sectionKey);
            int indexIdx = this.section2index.remove(sectionKey);
            if (indexIdx != -1) {
                this.terrainAreana.free(indexIdx);
            }
            this.translucencyQuadCounts.remove(sectionKey);
            //Clear the segment
            this.regionManager.removeSection(sectionIdx);
        }
    }

    public void destroy() {
        this.regionManager.destroy();
        this.terrainAreana.delete();
    }

    public void commitChanges() {
        this.regionManager.commitChanges();
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public void removeRegionById(int regionId) {
        if (!this.regionManager.regionExists(regionId)) return;
        long rk = this.regionManager.regionIdToKey(regionId);
        int X = SectionPos.x(rk)<<3;
        int Y = SectionPos.y(rk)<<2;
        int Z = SectionPos.z(rk)<<3;
        for (int x = X; x < X+8; x++) {
            for (int y = Y; y < Y+4; y++) {
                for (int z = Z; z < Z+8; z++) {
                    this.deleteSection(SectionPos.asLong(x, y, z));
                }
            }
        }
    }
}




