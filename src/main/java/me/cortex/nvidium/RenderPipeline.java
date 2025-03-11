package me.cortex.nvidium;

import java.util.BitSet;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3i;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedBufferData;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import org.lwjgl.opengl.GL11C;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.PrimaryTerrainRasterizer;
import me.cortex.nvidium.renderers.RegionRasterizer;
import me.cortex.nvidium.renderers.SectionRasterizer;
import me.cortex.nvidium.renderers.SortRegionSectionPhase;
import me.cortex.nvidium.renderers.TemporalTerrainRasterizer;
import me.cortex.nvidium.renderers.TranslucentTerrainRasterizer;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;

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

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*8+3*4+3+4+8+8+(4*4*4), 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
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

        int maxRegions = sectionManager.getRegionManager().maxRegions();

        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions*2L);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
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
    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {
        if (sectionManager.getRegionManager().regionCount() == 0) return;//Dont render anything if there is nothing to render

        final int DEBUG_RENDER_LEVEL = 0;//0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);
        
        int screenWidth = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
        int screenHeight = MinecraftClient.getInstance().getWindow().getFramebufferHeight();

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
                if ((Nvidium.config.region_keep_distance != 256 && Nvidium.config.region_keep_distance != 32) && !rm.withinSquare(Nvidium.config.region_keep_distance+4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(frustum, i)) {
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
            if (visibleRegions == 0) return;
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

        // Setup rendering state - AMD compatible version
        if (Nvidium.config.render_fog != compiledForFog) {
            reloadShaders();
            compiledForFog = Nvidium.config.render_fog;
        }

        //The real meat of the rendering happens here
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        // Standard OpenGL 4.3+ approach instead of NVIDIA unified memory
        // Bind buffers directly instead of using unified memory
        sectionManager.terrainAreana.buffer.bindBase(GL_SHADER_STORAGE_BUFFER, 0);
        regionVisibility.bindBase(GL_SHADER_STORAGE_BUFFER, 1);
        sectionVisibility.bindBase(GL_SHADER_STORAGE_BUFFER, 2); 
        terrainCommandBuffer.bindBase(GL_SHADER_STORAGE_BUFFER, 3);
        sceneUniform.bindBase(GL_UNIFORM_BUFFER, 0);
        transformationArray.bindBase(GL_UNIFORM_BUFFER, 1);
        originOffsetArray.bindBase(GL_UNIFORM_BUFFER, 2);
        
        // For debugging statistics if needed
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            //Reset the stats buffer
            nglClearNamedBufferSubData(statisticsBuffer.getId(), GL_R8UI, 0, 16, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
            statisticsBuffer.bindBase(GL_SHADER_STORAGE_BUFFER, 4);
        }

        glEnable(GL_DEPTH_TEST);
        
        // AMD-compatible early fragment testing (standard early-z)
        glEnable(GL_CULL_FACE);
        glDepthFunc(GL11C.GL_LEQUAL);
        
        int regionSortSize = 0;
        if (!regionsToSort.isEmpty()) {
            regionSortingList.bindBase(GL_SHADER_STORAGE_BUFFER, 6);
            // Upload sorting data
            long addr = uploadStream.upload(regionSortingList, 0, 4 + regionsToSort.size()*4);
            MemoryUtil.memPutInt(addr, regionsToSort.size());
            regionSortSize = regionsToSort.size();
            
            int i = 0;
            for (int regionId : regionsToSort) {
                MemoryUtil.memPutInt(addr + 4 + (i*4), regionId);
                i++;
            }
            regionsToSort.clear();
        }

        // Setup for depth-only pass (AMD-compatible)
        if (DEBUG_RENDER_LEVEL < 1) {
            glColorMask(false, false, false, false);
        }
        
        // Perform standard early-z occlusion culling instead of NVIDIA representative fragment test
        regionRasterizer.raster(visibleRegions);

        if (DEBUG_RENDER_LEVEL == 1) {
            glColorMask(false, false, false, false);
        }

        // Use standard GL43 memory barrier
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        if (DEBUG_RENDER_LEVEL == 2) {
            glColorMask(true, true, true, true);
        }
        if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
            glDepthMask(true);
        }

        sectionRasterizer.raster(visibleRegions);
        
        // Restore normal rendering state
        glDepthMask(true);
        glColorMask(true, true, true, true);

        // Standard GL43 memory barrier
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        // Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
        }

        // Do visibility tracking with standard depth testing
        {
            glDepthMask(false);
            glColorMask(false, false, false, false);
            
            // Use conservative depth testing instead of representative fragment test
            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);

            glDepthMask(true);
            glColorMask(true, true, true, true);
        }

        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        // Unbind buffers
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);
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
    public void renderTranslucent() {
        // Use standard OpenGL buffer binding
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        
        // Bind buffers directly using the standard approach
        sceneUniform.bindBase(GL_UNIFORM_BUFFER, 0);
        sectionManager.terrainAreana.buffer.bindBase(GL_SHADER_STORAGE_BUFFER, 0);
        regionVisibility.bindBase(GL_SHADER_STORAGE_BUFFER, 1);
        sectionVisibility.bindBase(GL_SHADER_STORAGE_BUFFER, 2); 
        terrainCommandBuffer.bindBase(GL_SHADER_STORAGE_BUFFER, 3);
        translucencyCommandBuffer.bindBase(GL_SHADER_STORAGE_BUFFER, 4);
        
        //Translucency sorting
        {
            glEnable(GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
            translucencyTerrainRasterizer.raster(prevRegionCount, translucencyCommandBuffer.getDeviceAddress());
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            glDisable(GL_DEPTH_TEST);
        }

        // Unbind buffers
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);

        //Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()){
            downloadStream.download(statisticsBuffer, 0, 4*4, (addr)-> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr+4);
                stats.quadCount = MemoryUtil.memGetInt(addr+8);
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
        terrainCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
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
            info.addAll(List.of(builder.toString().split("\n")));
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

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }
}