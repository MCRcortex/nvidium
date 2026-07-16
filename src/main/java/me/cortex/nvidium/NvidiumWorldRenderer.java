package me.cortex.nvidium;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

public class NvidiumWorldRenderer {
    private static final RenderDevice device = new RenderDevice();

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;
    private final RenderPipeline renderPipeline;


    //Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    //Note: the reason that asyncChunkTracker is passed in as an already constructed object is cause of the amount of argmuents it takes to construct it
    public NvidiumWorldRenderer() {
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit+1;
        //32 mb upload buffer
        this.uploadStream = new UploadingBufferStream(device, 32000000);
        //8 mb download buffer
        this.downloadStream = new DownloadTaskStream(device, frames, 8000000);

        update_allowed_memory();
        //this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, 150, 24, CompactChunkVertex.STRIDE);
        this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, Nvidium.config.use_sodium_vertex_format ? ChunkMeshFormats.COMPACT.getVertexFormat().getStride() : NvidiumCompactChunkVertex.STRIDE, this);
        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);
    }

    public void enqueueRegionSort(int regionId) {
        this.renderPipeline.enqueueRegionSort(regionId);
    }

    public void delete() {
        uploadStream.delete();
        downloadStream.delete();
        renderPipeline.delete();
        sectionManager.destroy();
    }

    public void reloadShaders() {
        renderPipeline.reloadShaders();
    }

    public void renderFrame(TerrainRenderPass pass, Viewport viewport, FogParameters fogParameters, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler) {
        renderPipeline.renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);

        while (sectionManager.terrainAreana.getUsedMB() > (max_geometry_memory - 100)) {
            renderPipeline.removeARegion();
        }

        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER && (System.currentTimeMillis() - last_sample_time) > 60000) {
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
        }
    }

    public void renderTranslucent(TerrainRenderPass pass, GpuSampler terrainSampler) {
        this.renderPipeline.renderTranslucent(pass, terrainSampler);
    }

    public void deleteSection(RenderSection section) {
        this.sectionManager.deleteSection(section);
    }

    public void uploadBuildResult(BuilderTaskOutput buildOutput) {
        if (buildOutput instanceof ChunkBuildOutput chunkBuildOutput) {
            this.sectionManager.uploadChunkBuildResult(chunkBuildOutput);
        }
        if (buildOutput instanceof ChunkSortOutput chunkSortOutput && chunkSortOutput.containsNewIndexData() &&
                Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            this.sectionManager.uploadChunkSort(chunkSortOutput);
        }
    }

    public void addDebugInfo(ArrayList<String> debugInfo) {
        debugInfo.add("Using nvidium renderer: "+ Nvidium.MOD_VERSION);
        /*
        debugInfo.add("Memory limit: " + max_geometry_memory + " mb");
        debugInfo.add("Terrain Memory MB: " +);
        debugInfo.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());
         */
        debugInfo.add("Mem" + (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?"":" (fallback)") + ": " +
                (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?
                        this.sectionManager.terrainAreana.getAllocatedMB() :
                        this.sectionManager.terrainAreana.getUsedMB())
                + "/"+ this.max_geometry_memory + String.format(", F: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());
        this.renderPipeline.addDebugInfo(debugInfo);
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

    public SectionManager getSectionManager() {
        return sectionManager;
    }

    public void setTransformation(int id, Matrix4fc transform) {
        this.renderPipeline.setTransformation(id, transform);
    }

    public void setOrigin(int id, int x, int y, int z) {
        this.renderPipeline.setOrigin(id, x, y, z);
    }

    public int getMaxGeometryMemory() {
        return (int) max_geometry_memory;
    }
}
