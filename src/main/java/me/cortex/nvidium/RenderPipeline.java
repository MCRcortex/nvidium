package me.cortex.nvidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.PrimaryTerrainRasterizer;
import me.cortex.nvidium.renderers.RegionRasterizer;
import me.cortex.nvidium.renderers.SectionRasterizer;
import me.cortex.nvidium.renderers.TranslucentTerrainRasterizer;
import me.cortex.nvidium.util.TickableManager;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.format.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.glClearNamedBufferSubData;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
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

    private final PrimaryTerrainRasterizer terrainRasterizer;
    private final RegionRasterizer regionRasterizer;
    private final SectionRasterizer sectionRasterizer;
    private final TranslucentTerrainRasterizer translucencyTerrainRasterizer;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+8*6+3, 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;

    public RenderPipeline() {
        sectionManager = new SectionManager(device, 32, 24, SodiumClientMod.options().advanced.cpuRenderAheadLimit+1, CompactChunkVertex.STRIDE);
        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        int maxRegions = sectionManager.getRegionManager().maxRegions();
        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE+ maxRegions*2);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L * 2);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L*7);

        //Preset the bias
        glPolygonOffset( -0.001f, -0.1f);
    }

    private int prevRegionCount;
    private int frameId;

    public void renderFrame(Frustum frustum, ChunkRenderMatrices crm, ChunkCameraContext cam) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently
        if (sectionManager.getRegionManager().regionCount() == 0) return;//Dont render anything if there is nothing to render
        Vector3i chunkPos = new Vector3i(((int)Math.floor(cam.posX))>>4, ((int)Math.floor(cam.posY))>>4, ((int)Math.floor(cam.posZ))>>4);


        int visibleRegions = 0;
        int playerRegion = -1;
        int playerRegionId = -1;
        //Enqueue all the visible regions
        {
            var rm = sectionManager.getRegionManager();
            //The region data indicies is located at the end of the sceneUniform
            //TODO: Sort the regions from closest to furthest from the camera
            IntSortedSet regions = new IntAVLTreeSet();
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (rm.isRegionVisible(frustum, i)) {
                    regions.add((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z)<<16)|i);

                    visibleRegions++;
                }
                if (rm.regionIsAtPos(i, chunkPos.x>>3, chunkPos.y>>2, chunkPos.z>>3)) {
                    playerRegionId = i;
                }
            }
            if (visibleRegions == 0) return;
            long addr = sectionManager.uploadStream.getUpload(sceneUniform, SCENE_SIZE, visibleRegions*2);
            int j = 0;
            for (int i : regions) {
                if (((short)i) == playerRegionId) {
                    playerRegion = j;
                }
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) i);
                j++;
            }
        }

        {
            //TODO: maybe segment the uniform buffer into 2 parts, always updating and static where static holds pointers
            Vector3f delta = new Vector3f((cam.blockX-(chunkPos.x<<4))+cam.deltaX, (cam.blockY-(chunkPos.y<<4))+cam.deltaY, (cam.blockZ-(chunkPos.z<<4))+cam.deltaZ);

            long addr = sectionManager.uploadStream.getUpload(sceneUniform, 0, SCENE_SIZE);
            new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta.negate())//Translate the subchunk position//TODO: THIS
                    .getToAddress(addr);
            addr += 4*4*4;
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in//TODO: THIS
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
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress()+sectionManager.getRegionManager().maxRegions()*256L);
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (frameId++));
        }
        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data

        //TODO: FIXME: THIS FEELS ILLEGAL
        TickableManager.TickAll();

        if (false) return;
        int err;
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
            //glEnable(GL_CONSERVATIVE_RASTERIZATION_NV);
            //glEnable(GL_SAMPLE_SHADING);
            //glMinSampleShadingARB(0.0f);
            //glDisable(GL_CULL_FACE);
            terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
            //glEnable(GL_CULL_FACE);
        }

        glEnable( GL_POLYGON_OFFSET_FILL );
        glPolygonOffset( 0, -30);//TODO: OPTIMZIE THIS

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        //glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glColorMask(false, false, false, false);
        glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        regionRasterizer.raster(visibleRegions);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);


        {//This uses the clear buffer to set the byte for the region the player is standing in, this should be cheaper than comparing it on the gpu
            if (playerRegion != -1) {
                glClearNamedBufferSubData(regionVisibility.getId(), GL_R8UI, playerRegion, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte)(frameId-1)});
            }
        }

        if (false) {
            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            glColorMask(true, true, true, true);
        }
        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }

        {//This uses the clear buffer to set the byte for the section the player is standing in, this should be cheaper than comparing it on the gpu
            if (playerRegionId != -1) {
                int id = sectionManager.getSectionRegionIndex(chunkPos.x, chunkPos.y, chunkPos.z);
                if (id != -1) {
                    id |= playerRegionId<<8;
                    glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, id, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte) (frameId - 1)});
                }
            }
        }

        prevRegionCount = visibleRegions;
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
        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();
        //TODO: Delete rest of the render passes
    }
}