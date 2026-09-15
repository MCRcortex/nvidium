package me.cortex.nvidium;

import com.mojang.blaze3d.textures.GpuSampler;
import it.unimi.dsi.fastutil.ints.*;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.mixin.minecraft.TextureAtlasAccessor;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.*;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gpu.GPULimits;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVShaderBufferStore.GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;

public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;
    private CmdBufferBuilder cmdBufferBuilder;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(
                    4*4*4 +  // mat4     MVP
                    4*4*4 + // mat4      MVPInv (Optional)
                    4*4 +   // ivec4     chunkPosition
                    4*4 +   // vec4      subchunkOffset
                    8 +     // uint16_t  *regionIndicies
                    8 +     // Region    *regionData
                    8 +     // Section   *sectionData
                    8 +     // uint8_t   *regionVisibility
                    8 +     // uint8_t   *sectionVisibility
                    8 +     // u8vec3    *sectionIndices
                    8 +     // uvec2     *terrainCommandBuffer
                    8 +     // uvec2     *translucencyCommandBuffer
                    8 +     // uvec2     *temporalCommandBuffer
                    8 +     // uint16_t  *sortingRegionList
                    8 +     // Vertex    *terrainData
                    8 +     // uint      *translucencyIndexData TODO
                    8 +     // mat4      *transformationArray
                    8 +     // uint64_t  *originArray
                    8 +     // uint32_t  *statistics_buffe
                    4*2 +   // vec2      screenSize
                    4*4 +   // vec4      fogColour
                    2*4 +   // vec2      environmentFog
                    2*4 +   // vec2      renderFog
                    4*2 +   // vec2      texCoordShrink
                    4*2 +   // vec2      texelSize
                    4 +     // uint      flags
                    2 +     // uint16_t  regionCount
                    1       // uint8_t   frameId
            , 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer sectionIndices;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
    private final IDeviceMappedBuffer temporalCommandBuffer;
    private final IDeviceMappedBuffer regionSortingList;
    private final IDeviceMappedBuffer statisticsBuffer;
    private final IDeviceMappedBuffer transformationArray;
    private final IDeviceMappedBuffer originOffsetArray;

    private final BitSet regionVisibilityTracker;

    //Set of regions that need to be sorted
    private final IntSet regionsToSort = new IntOpenHashSet();

    private static final class Statistics {
        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
        public int cullCount;
    }

    private final Statistics stats;

    public RenderPipeline(RenderDevice device, UploadingBufferStream uploadStream, DownloadTaskStream downloadStream, SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;
        this.compiledForFog = Nvidium.config.render_fog;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
        cmdBufferBuilder = new CmdBufferBuilder();

        int maxRegions = sectionManager.getRegionManager().maxRegions();

        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions*2L);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        sectionIndices = device.createDeviceOnlyMappedBuffer(maxRegions * 256L * 3L);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        temporalCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        regionSortingList = device.createDeviceOnlyMappedBuffer(maxRegions*2L);
        this.transformationArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
        this.originOffsetArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * 8);

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = device.createDeviceOnlyMappedBuffer(4*4);
        stats = new Statistics();


        //Initialize the transformationArray buffer to the identity affine transform
        {
            long ptr = this.uploadStream.upload(this.transformationArray, 0, RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4*4*4;
            }
        }
        //Clear the origin offset
        nglClearNamedBufferData(this.originOffsetArray.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);


    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4*4*4), 4*4*4);
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x&0x1ffffff;
        pos |= ((long)(z&0x1ffffff))<<25;
        pos |= ((long)(y&0x3fff))<<50;

        MemoryUtil.memPutLong(ptr, pos);
    }

    private int prevRegionCount;
    private int frameId;
    private boolean compiledForFog = false;

    //TODO FIXME: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    //NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently
    public void renderFrame(TerrainRenderPass pass, Viewport viewport, FogParameters fogParameters, ChunkRenderMatrices crm, double px, double py, double pz, GpuSampler terrainSampler) {
        if (sectionManager.getRegionManager().regionCount() == 0) return;//Dont render anything if there is nothing to render

        final int DEBUG_RENDER_LEVEL = 0;//0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        /*
        for (int i = 0; i <3*3*3;i++) {
            new NvidiumAPI("nvidium").setRegionTransformId(1, i%3, (i/3)%3, ((i/3)/3)%3);
        }
        new NvidiumAPI("nvidium").setTransformation(1, new Matrix4f().identity().scale(1,1  ,1));
        new NvidiumAPI("nvidium").setOrigin(1, 0,0,0);
           */

        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);
        //  /tp @p 0.0 -1.62 0.0 0 0
        //Clear the first gl error, not our fault
        //glGetError();

        int screenWidth = Minecraft.getInstance().getWindow().getWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getHeight();

        var textureAtlas = (TextureAtlasAccessor) Minecraft.getInstance()
                .getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);

        double subTexelPrecision = (1 << GPULimits.getSubTexelPrecisionBits());
        double subTexelOffset = 1.0f / CompactChunkVertex.TEXTURE_MAX_VALUE;

        float subTexelWidth = (float)(subTexelOffset - (((1.0D / textureAtlas.nvidium$getWidth()) / subTexelPrecision)));
        float subTexelHeight = (float)(subTexelOffset - (((1.0D / textureAtlas.nvidium$getHeight()) / subTexelPrecision)));

        int visibleRegions = 0;

        long queryAddr = 0;
        var rm = sectionManager.getRegionManager();

        short[] regionMap;
        //Enqueue all the visible regions
        {

            //The region data indicies is located at the end of the sceneUniform
            IntSortedSet regions = new IntAVLTreeSet();
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if (!Nvidium.config.keepUntilVramLimit()
                        && !rm.withinSquare(Nvidium.config.gpuKeepChunks() + 4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(viewport, i)) {
                    //Note, its sorted like this because of overdraw, also the translucency command buffer is written to
                    // in a reverse order to this in the section_raster/task.glsl shader
                    regions.add(((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z))<<16)|i);
                    visibleRegions++;
                    regionVisibilityTracker.set(i);

                    if (rm.isRegionInACameraAxis(i, px, py, pz)) {
                        regionsToSort.add(i);
                    }

                } else {
                    if (regionVisibilityTracker.get(i)) {//Going from visible to non visible
                        //Clear the visibility bits
                        if (Nvidium.config.enable_temporal_coherence) {
                            nglClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, (long) i << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
                        }
                    }
                    regionVisibilityTracker.clear(i);
                }

            }

            regionMap = new short[regions.size()];
            if (visibleRegions == 0) {
                prevRegionCount = 0;
                return;
            }
            long addr = uploadStream.upload(sceneUniform, SCENE_SIZE, visibleRegions*2);
            queryAddr = addr;//This is ungodly hacky
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) i);
                j++;
            }

            if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
                stats.frustumCount = regions.size();
            }
        }

        {
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            long addr = uploadStream.upload(sceneUniform, 0, SCENE_SIZE);
            new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta)//Translate the subchunk position
                    .getToAddress(addr);
            addr += 4*4*4;
            if (this.compiledForFog) {
                new Matrix4f(crm.projection())
                        .mul(crm.modelView())
                        .invert()
                        .getToAddress(addr);
                addr += 4*4*4;
            }
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in
            addr += 16;
            new Vector4f(delta,0).getToAddress(addr);//Subchunk offset (note, delta is already negated)
            addr += 16;
            MemoryUtil.memPutLong(addr, sceneUniform.getDeviceAddress() + SCENE_SIZE);//Put in the location of the region indexs
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getSectionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionIndices.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, translucencyCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, temporalCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionSortingList.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.transformationArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.originOffsetArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, statisticsBuffer == null?0:statisticsBuffer.getDeviceAddress());//Logging buffer
            addr += 8;
            //Convert it into the expected size values and floats
            MemoryUtil.memPutFloat(addr, ((float)screenWidth)/2);
            addr += 4;
            MemoryUtil.memPutFloat(addr, ((float)screenHeight)/2);
            addr += 4;
            new Vector4f(fogParameters.red(), fogParameters.green(), fogParameters.blue(), fogParameters.alpha()).getToAddress(addr);
            addr += 16;
            new Vector2f(fogParameters.environmentalStart(), fogParameters.environmentalEnd()).getToAddress(addr);
            addr += 8;
            new Vector2f(fogParameters.renderStart(), fogParameters.renderEnd()).getToAddress(addr);
            addr += 8;
            MemoryUtil.memPutFloat(addr, subTexelWidth);
            addr += 4;
            MemoryUtil.memPutFloat(addr, subTexelHeight);
            addr += 4;
            MemoryUtil.memPutFloat(addr, 1.0f / textureAtlas.nvidium$getWidth());
            addr += 4;
            MemoryUtil.memPutFloat(addr, 1.0f / textureAtlas.nvidium$getHeight());
            addr += 4;
            int flags = 0;
            flags |= SodiumClientMod.options().performance.useBlockFaceCulling?1:0;
            if (Minecraft.getInstance().options.textureFiltering().get() == TextureFilteringMethod.RGSS)
                flags |= 2;
            MemoryUtil.memPutInt(addr, flags);//Flags
            addr += 4;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (frameId++));
        }

        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.NONE) {
            regionsToSort.clear();
        }

        int regionSortSize = this.regionsToSort.size();

        if (regionSortSize != 0){
            long regionSortUpload = uploadStream.upload(regionSortingList, 0, regionSortSize * 2);
            for (int region : regionsToSort) {
                MemoryUtil.memPutShort(regionSortUpload, (short) region);
                regionSortUpload += 2;
            }
            regionsToSort.clear();
        }

        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data
        uploadStream.commit();

        TickableManager.TickAll();

        //if ((err = glGetError()) != 0) {
        //    throw new IllegalStateException("GLERROR: "+err);
        //}


        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(pass, prevRegionCount, terrainCommandBuffer.getDeviceAddress(), terrainSampler);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL != 1) {
            glColorMask(false, false, false, false);
        }
        if (DEBUG_RENDER_LEVEL == 0)
        {
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }

        regionRasterizer.raster(visibleRegions);

        if (DEBUG_RENDER_LEVEL == 1) {
            glColorMask(false, false, false, false);
        }

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        //glColorMask(true, true, true, true);

        if (DEBUG_RENDER_LEVEL == 2) {
            glColorMask(true, true, true, true);
        }
        if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
            glDepthMask(true);
        }

        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        cmdBufferBuilder.dispatch(visibleRegions);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(pass, visibleRegions, temporalCommandBuffer.getDeviceAddress(), terrainSampler);
        }


        {//Do proper visibility tracking
            glDepthMask(false);
            glColorMask(false, false, false, false);
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);

            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            glDepthMask(true);
            glColorMask(true, true, true, true);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL11C.GL_GEQUAL);

        //if ((err = glGetError()) != 0) {
        //    throw new IllegalStateException("GLERROR: "+err);
        //}
    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    public void removeARegion() {
        removeRegion(regionVisibilityTracking.findMostLikelyLeastSeenRegion(sectionManager.getRegionManager().maxRegionIndex()));
    }

    /*
    private void setRegionVisible(long rid) {
        glClearNamedBufferSubData(regionVisibility.getId(), GL_R8UI, rid, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte)(1)});
    }*/

    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent(TerrainRenderPass pass, GpuSampler terrainSampler) {
        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Need to rebind the uniform since it might have been wiped
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        //Translucency sorting
        {
            glEnable(GL_DEPTH_TEST);
            translucencyTerrainRasterizer.raster(pass, prevRegionCount, translucencyCommandBuffer.getDeviceAddress(), terrainSampler);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);




        //Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()){
            downloadStream.download(statisticsBuffer, 0, 4*4, (addr)-> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr+4);
                stats.quadCount = MemoryUtil.memGetInt(addr+8);
                stats.cullCount = MemoryUtil.memGetInt(addr+12);
            });
        }


        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            //glMemoryBarrier(GL_ALL_BARRIER_BITS);
            //Stupid bloody nvidia not following spec forcing me to use a upload stream
            long upload = this.uploadStream.upload(statisticsBuffer, 0, 4*4);
            MemoryUtil.memSet(upload, 0, 4*4);
            //glClearNamedBufferSubData(statisticsBuffer.getId(), GL_R32UI, 0, 4 * 4, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        }
    }

    public void delete() {
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        sectionIndices.delete();
        terrainCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        temporalCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        cmdBufferBuilder.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ").append(stats.frustumCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ").append(stats.regionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ").append(stats.sectionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ").append(stats.quadCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.CULL.ordinal()) {
                builder.append(", C: ").append(stats.cullCount);
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
        info.add("Primary Terrain frame time: " +  String.format("%.03f", terrainRasterizer.getTiming().getAverageMs()) + "ms");
        info.add("Translucent frame time: " +  String.format("%.03f", translucencyTerrainRasterizer.getTiming().getAverageMs()) + "ms");
        if (Nvidium.config.enable_temporal_coherence) {
            info.add("Temporal frame time: " +  String.format("%.03f", temporalRasterizer.getTiming().getAverageMs()) + "ms");
        }
        info.add("Region Raster time: " +  String.format("%.03f", regionRasterizer.getTiming().getAverageMs()) + "ms");
        info.add("Section Raster time: " +  String.format("%.03f", sectionRasterizer.getTiming().getAverageMs()) + "ms");
        info.add("CmdBufferBuilder time: " +  String.format("%.03f", cmdBufferBuilder.getTiming().getAverageMs()) + "ms");
        if (Nvidium.config.translucency_sorting_level != TranslucencySortingLevel.NONE) {
            info.add("SectionSorter time: " +  String.format("%.03f", regionSectionSorter.getTiming().getAverageMs()) + "ms");
        }
    }

    public void reloadShaders() {
        this.compiledForFog = Nvidium.config.render_fog;
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        cmdBufferBuilder.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
        cmdBufferBuilder = new CmdBufferBuilder();
    }
}