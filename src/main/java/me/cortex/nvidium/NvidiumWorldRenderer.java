package me.cortex.nvidium;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.lod.LodSprites;
import me.cortex.nvidium.lod.LodSystem;
import me.cortex.nvidium.persist.PersistentMesh;
import me.cortex.nvidium.persist.PersistentMeshLoader;
import me.cortex.nvidium.persist.PersistentSectionStore;
import me.cortex.nvidium.persist.PersistentWorldPaths;
import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;
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
    private final PersistentSectionStore persistentStore;
    private final PersistentMeshLoader persistentLoader;
    private final LodSystem lodSystem;


    //Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    //Note: the reason that asyncChunkTracker is passed in as an already constructed object is cause of the amount of argmuents it takes to construct it
    public NvidiumWorldRenderer(ClientLevel level) {
        int frames = 3;
        //32 mb upload buffer
        this.uploadStream = new UploadingBufferStream(device, 32000000);
        //8 mb download buffer
        this.downloadStream = new DownloadTaskStream(device, frames, 8000000);

        update_allowed_memory();
        int stride = Nvidium.config.use_sodium_vertex_format ? ChunkMeshFormats.COMPACT.getVertexFormat().getVertexSize() : NvidiumCompactChunkVertex.STRIDE;
        this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, stride, this);
        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);

        PersistentSectionStore store = null;
        PersistentMeshLoader loader = null;
        LodSystem lod = null;
        if (Nvidium.config.diskPersistence() && level != null) {
            try {
                Path root = PersistentWorldPaths.resolve(level);
                store = new PersistentSectionStore(root, stride);
                if (Nvidium.config.lodEnabled()) {
                    lod = new LodSystem(root, this.sectionManager);
                }
                loader = new PersistentMeshLoader(store, this.sectionManager, lod);
                LodSystem lodRef = lod;
                PersistentMeshLoader loaderRef = loader;
                this.sectionManager.setRegionRemovedCallback(rk -> {
                    loaderRef.allowReload(rk);
                    if (lodRef != null) {
                        lodRef.forgetRegion(rk);
                    }
                });
                LodSprites.refresh();
            } catch (IOException e) {
                Nvidium.LOGGER.error("Failed to open persistent mesh store, continuing without disk cache", e);
                if (loader != null) {
                    loader.close();
                    loader = null;
                }
                if (lod != null) {
                    lod.close();
                    lod = null;
                }
                if (store != null) {
                    store.close();
                    store = null;
                }
            }
        }
        this.persistentStore = store;
        this.persistentLoader = loader;
        this.lodSystem = lod;
    }

    public void enqueueRegionSort(int regionId) {
        this.renderPipeline.enqueueRegionSort(regionId);
    }

    public void delete() {
        if (this.persistentLoader != null) {
            this.persistentLoader.close();
        }
        if (this.lodSystem != null) {
            this.lodSystem.close();
        }
        if (this.persistentStore != null) {
            this.persistentStore.close();
        }
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

        if (this.persistentLoader != null) {
            this.persistentLoader.tick(x, y, z, sectionManager.terrainAreana.getUsedMB(), (int) this.max_geometry_memory);
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
            persistBuildResult(chunkBuildOutput);
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
        debugInfo.add("Keep: " + (Nvidium.config.keepUntilVramLimit()
                ? "all (VRAM)"
                : Nvidium.config.gpuKeepChunks() + " chunks")
                + ", GPU sections: " + this.sectionManager.getGpuSectionCount());
        if (this.persistentStore != null) {
            debugInfo.add("Disk: " + this.persistentStore.storedCount() + " sections, "
                    + (this.persistentStore.diskBytes() / (1024 * 1024)) + "MB, wrQ: "
                    + this.persistentStore.pendingWrites()
                    + (this.persistentLoader != null ? ", ldQ: " + this.persistentLoader.uploadQueue()
                    + ", loaded: " + this.persistentLoader.loadedFromDisk() : ""));
        } else {
            debugInfo.add("Disk: off");
        }
        if (this.lodSystem != null) {
            debugInfo.add(this.lodSystem.debugLine());
        }
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

    private void persistBuildResult(ChunkBuildOutput result) {
        if (this.persistentStore == null) {
            return;
        }
        long sectionKey = SectionPos.asLong(result.section.getChunkX(), result.section.getChunkY(), result.section.getChunkZ());
        var output = ((IRepackagedResult) result).getOutput();
        if (output == null || output.quads() == 0) {
            this.persistentStore.remove(sectionKey);
            if (this.lodSystem != null) {
                this.lodSystem.ingest(Minecraft.getInstance().level, result.section.getChunkX(), result.section.getChunkY(), result.section.getChunkZ());
            }
            return;
        }
        try {
            this.persistentStore.put(PersistentMesh.from(sectionKey, output));
        } catch (Exception e) {
            Nvidium.LOGGER.error("Failed to snapshot section {} for disk cache", sectionKey, e);
        }
        if (this.lodSystem != null) {
            this.lodSystem.onUploaded(sectionKey, (byte) 0);
            var level = Minecraft.getInstance().level;
            if (level != null) {
                this.lodSystem.ingest(level, result.section.getChunkX(), result.section.getChunkY(), result.section.getChunkZ());
            }
        }
    }
}
