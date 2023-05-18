package me.cortex.nvidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.format.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.client.MinecraftClient;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.ArrayList;
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


/*
var hm = new HashSet<Long>();
for (int i = 0; i < rm.maxRegionIndex(); i++) {
    if (rm.isRegionVisible(frustum, i)) {
        if(rm.regions[i] != null) {
            hm.add(ChunkPos.toLong(rm.regions[i].rx, rm.regions[i].rz))
        }
    }
}
hm.size()
 */
public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private static final RenderDevice device = new RenderDevice();

    public final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private final PrimaryTerrainRasterizer terrainRasterizer;
    private final RegionRasterizer regionRasterizer;
    private final SectionRasterizer sectionRasterizer;
    private final TemporalTerrainRasterizer temporalRasterizer;
    private final TranslucentTerrainRasterizer translucencyTerrainRasterizer;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*6+3*4+3, 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final int bufferSizesMB;

    private final BitSet regionVisibilityTracker;

    public RenderPipeline() {
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit+1;
        this.uploadStream = new UploadingBufferStream(device, frames, 160000000);
        this.downloadStream = new DownloadTaskStream(device, frames, 16000000);
        sectionManager = new SectionManager(device, uploadStream, MinecraftClient.getInstance().options.getClampedViewDistance() + Nvidium.config.extra_rd, 24, CompactChunkVertex.STRIDE);
        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();

        int maxRegions = sectionManager.getRegionManager().maxRegions();
        int cbs = sectionManager.getTotalBufferSizes();
        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE+ maxRegions*2L);
        cbs += SCENE_SIZE+ maxRegions*2L;
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        cbs += maxRegions;
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        cbs += maxRegions * 256L;
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L*7);
        cbs += maxRegions*8L*7;

        regionVisibilityTracker = new BitSet(maxRegions);

        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        bufferSizesMB = cbs/(1024*1024);
    }

    private int prevRegionCount;
    private int frameId;

    //ISSUE TODO: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Frustum frustum, ChunkRenderMatrices crm, double px, double py, double pz) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently

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
            //TODO: Sort the regions from closest to furthest from the camera
            IntSortedSet regions = new IntAVLTreeSet();
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if ((Nvidium.config.region_keep_distance != 256 && Nvidium.config.region_keep_distance != 32) && !rm.withinSquare(Nvidium.config.region_keep_distance+4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(frustum, i)) {
                    regions.add((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z)<<16)|i);
                    visibleRegions++;
                    regionVisibilityTracker.set(i);
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
            long addr = sectionManager.uploadStream.getUpload(sceneUniform, SCENE_SIZE, visibleRegions*2);
            queryAddr = addr;//This is ungodly hacky
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) i);
                j++;
            }

        }

        {
            //TODO: maybe segment the uniform buffer into 2 parts, always updating and static where static holds pointers
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            long addr = sectionManager.uploadStream.getUpload(sceneUniform, 0, SCENE_SIZE);
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
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionDataAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getSectionDataAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
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
        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data

        //TODO: FIXME: THIS FEELS ILLEGAL
        TickableManager.TickAll();
        if (false) return;
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

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);


        {//This uses the clear buffer to set the byte for the region the player is standing in, this should be cheaper than comparing it on the gpu
            outerLoop:
            for (int i = 0; i < visibleRegions; i++) {
                int rid = MemoryUtil.memGetShort(queryAddr+(i<<1));
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if (rm.regionIsAtPos(rid, (blockPos.x+x) >> 7, (blockPos.y+y) >> 6, (blockPos.z+z) >> 7)) {
                                setRegionVisible(i);
                                continue outerLoop;
                            }
                        }
                    }
                }
            }
        }

        /*
        {//Download the region visibility from the gpu, used for determining culling
            downloadStream.download(regionVisibility, 0, visibleRegions, addr->{
                for (long i = 0; i < size; i++) {
                    System.out.println(MemoryUtil.memGetByte(addr + i));
                }
            });
        }*/

        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        {//This uses the clear buffer to set the byte for the section the player is standing in, this should be cheaper than comparing it on the gpu
            int msk = 0;//This is such a dumb way to do this but it works
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        int mid = 1<<(((chunkPos.x - ((blockPos.x + x) >> 4))+1)+((chunkPos.y - ((blockPos.y + y) >> 4))+1)*3+((chunkPos.z - ((blockPos.z + z) >> 4))+1)*9);
                        if ((msk&mid)==0) {
                            setSectionVisible((blockPos.x + x) >> 4, (blockPos.y + y) >> 4, (blockPos.z + z) >> 4);
                            msk |= mid;
                        }
                    }
                }
            }
        }

        prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            //glFinish();
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
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
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);


        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }


        if (Nvidium.config.enable_temporal_coherence && sectionManager.terrainAreana.getUsedMB()>(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?Nvidium.config.geometry_removing_memory_size:(Nvidium.config.fallback_allocation_size-50))) {
            removeRegion(regionVisibilityTracking.findMostLikelyLeastSeenRegion(sectionManager.getRegionManager().maxRegionIndex()));
        }
        //glFinish();
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    private void setRegionVisible(long rid) {
        glClearNamedBufferSubData(regionVisibility.getId(), GL_R8UI, rid, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte)(1)});
    }

    private void setSectionVisible(int cx, int cy, int cz) {
        int rid = sectionManager.getRegionManager().regionKeyToId(RegionManager.getRegionKey(cx, cy, cz));
        if (rid != -1) {
            int id = sectionManager.getSectionRegionIndex(cx, cy, cz);
            if (id != -1) {
                id |= rid << 8;
                glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, id, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte) (-1)});
            }
        }
    }


    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent() {
        //if (true) return;

        //Memory barrier for the command buffer
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

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
            //The + 8*6 is the offset to the unassigned dispatch
            translucencyTerrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
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
        sectionManager.delete();
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();

        downloadStream.delete();
        uploadStream.delete();
    }

    public int getOtherBufferSizesMB() {
        return bufferSizesMB;
    }

    public void addDebugInfo(List<String> info) {
        info.add("Using nvidium renderer");
        info.add("Other Memory MB: " + getOtherBufferSizesMB());
        info.add("Terrain Memory MB: " + sectionManager.terrainAreana.getAllocatedMB()+(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?"":" (fallback mode)"));
        info.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        info.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());

    }
}