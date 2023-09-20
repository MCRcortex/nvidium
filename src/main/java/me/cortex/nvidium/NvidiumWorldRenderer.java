package me.cortex.nvidium;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

public class NvidiumWorldRenderer {
    private static final RenderDevice device = new RenderDevice();

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;
    private final RenderPipeline renderPipeline;

    private final AsyncOcclusionTracker asyncChunkTracker;

    //Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    //Note: the reason that asyncChunkTracker is passed in as an already constructed object is cause of the amount of argmuents it takes to construct it
    public NvidiumWorldRenderer(AsyncOcclusionTracker asyncChunkTracker) {
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit+1;
        this.uploadStream = new UploadingBufferStream(device, frames, 250000000);
        this.downloadStream = new DownloadTaskStream(device, frames, 16000000);

        update_allowed_memory();
        this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, 150, 24, CompactChunkVertex.STRIDE);
        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);

        this.asyncChunkTracker = asyncChunkTracker;
    }

    public void delete() {
        uploadStream.delete();
        downloadStream.delete();
        renderPipeline.delete();
        sectionManager.delete();
        asyncChunkTracker.delete();
    }

    public void reloadShaders() {
        renderPipeline.reloadShaders();
    }

    public void renderFrame(Viewport viewport, ChunkRenderMatrices matrices, double x, double y, double z) {
        renderPipeline.renderFrame(viewport, matrices, x, y, z);

        if (sectionManager.terrainAreana.getUsedMB()>(max_geometry_memory-50)) {
            renderPipeline.removeARegion();
        }

        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER && (System.currentTimeMillis() - last_sample_time) > 60000) {
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
        }
        RenderLayer.getSolid().endDrawing();
    }

    public void renderTranslucent() {
        //Why is minecraft like this
        if (MinecraftClient.isFabulousGraphicsOrBetter()) {
            MinecraftClient.getInstance().worldRenderer.getTranslucentFramebuffer().beginWrite(false);
        }

        renderPipeline.renderTranslucent();

        if (MinecraftClient.isFabulousGraphicsOrBetter()) {
            MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        }
        RenderLayer.getTranslucent().endDrawing();
    }

    public void deleteSection(RenderSection section) {
        sectionManager.deleteSection(section);
    }

    public void uploadBuildResult(ChunkBuildOutput buildOutput) {
        sectionManager.uploadChunkBuildResult(buildOutput);
    }

    public void addDebugInfo(ArrayList<String> debugInfo) {
        debugInfo.add("Using nvidium renderer: "+ Nvidium.MOD_VERSION);
        debugInfo.add("Memory limit: " + max_geometry_memory + " mb");
        debugInfo.add("Terrain Memory MB: " + sectionManager.terrainAreana.getAllocatedMB()+(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?"":" (fallback mode)"));
        debugInfo.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());
        renderPipeline.addDebugInfo(debugInfo);
    }


    private void update_allowed_memory() {
        if (Nvidium.config.automatic_memory) {
            max_geometry_memory = (glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024) + (sectionManager==null?0:sectionManager.terrainAreana.getMemoryUsed()/(1024*1024));
            max_geometry_memory -= 1024;//Minus 1gb of vram
            max_geometry_memory = Math.max(2048, max_geometry_memory);//Minimum 2 gb of vram
        } else {
            max_geometry_memory = Nvidium.config.max_geometry_memory;
        }
    }

    public void update(Camera camera, Viewport viewport, int frame, boolean spectator) {
        asyncChunkTracker.update(viewport);
    }

    public int getAsyncFrameId() {
        return asyncChunkTracker.getFrame();
    }

    public List<RenderSection> getSectionsWithEntities() {
        return asyncChunkTracker.getLatestSectionsWithEntities();
    }

    @Nullable
    public Sprite[] getAnimatedSpriteSet() {
        return asyncChunkTracker.getVisibleAnimatedSprites();
    }
}
