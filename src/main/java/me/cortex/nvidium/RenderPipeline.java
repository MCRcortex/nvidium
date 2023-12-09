package me.cortex.nvidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.glClearNamedBufferSubData;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;


//TODO: extract out sectionManager, uploadStream, downloadStream and other funky things to an auxiliary parent NvidiumWorldRenderer class
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
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*7+3*4+3+4, 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
    private final IDeviceMappedBuffer regionSortingList;
    private final IDeviceMappedBuffer statisticsBuffer;

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

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = device.createDeviceOnlyMappedBuffer(4*4);
        stats = new Statistics();
    }

    private int prevRegionCount;
    private int frameId;

    //ISSUE TODO: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently

        if (sectionManager.getRegionManager().regionCount() == 0) return;//Dont render anything if there is nothing to render
        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);
        //  /tp @p 0.0 -1.62 0.0 0 0
        //Clear the first gl error, not our fault
        glGetError();
        int err;

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
                //TODO: fog culling/region removal cause with bobby the removal distance is huge and people run out of vram very fast


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
                            glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, (long) i << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{0});
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

        {
            //TODO: maybe segment the uniform buffer into 2 parts, always updating and static where static holds pointers
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            long addr = uploadStream.upload(sceneUniform, 0, SCENE_SIZE);
            var mvp =new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta)//Translate the subchunk position
                    .getToAddress(addr);
            addr += 4*4*4;
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in//TODO: THIS
            addr += 16;
            new Vector4f(delta,0).getToAddress(addr);//Subchunk offset (note, delta is already negated)
            addr += 16;
            new Vector4f(RenderSystem.getShaderFogColor()).getToAddress(addr);
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
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, translucencyCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionSortingList.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, statisticsBuffer == null?0:statisticsBuffer.getDeviceAddress());//Logging buffer
            addr += 8;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogStart());//FogStart
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogEnd());//FogEnd
            addr += 4;
            MemoryUtil.memPutInt(addr, RenderSystem.getShaderFogShape().getId());//IsSphericalFog
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

        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }


        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glColorMask(false, false, false, false);
        glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        regionRasterizer.raster(visibleRegions);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        //glColorMask(true, true, true, true);
        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
        }

        //Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()){
            downloadStream.download(statisticsBuffer, 0, 4*4, (addr)-> {
                stats.regionCount = MemoryUtil.memGetInt(addr);
                stats.sectionCount = MemoryUtil.memGetInt(addr+4);
                stats.quadCount = MemoryUtil.memGetInt(addr+8);
            });
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



        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);


        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }

        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            //glMemoryBarrier(GL_ALL_BARRIER_BITS);
            //Stupid bloody nvidia not following spec forcing me to use a upload stream
            long upload = this.uploadStream.upload(statisticsBuffer, 0, 4*4);
            MemoryUtil.memSet(upload, 0, 4*4);
            //glClearNamedBufferSubData(statisticsBuffer.getId(), GL_R32UI, 0, 4 * 4, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        }
    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    //TODO: refactor to different location
    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    //TODO: refactor out of the render pipeline along with regionVisibilityTracking and removeRegion and statistics
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
        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Need to rebind the uniform since it might have been wiped
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

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

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
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

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: \n");
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("Frustum: ").append(stats.frustumCount).append("\n");
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append("Regions: ").append(stats.regionCount).append("\n");
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append("Sections: ").append(stats.sectionCount).append("\n");
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append("Quads: ").append(stats.quadCount).append("\n");
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
    }

    public void reloadShaders() {
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