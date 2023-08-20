package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineSetter;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class MixinRenderRegionManager implements IRenderPipelineSetter {


    @Shadow protected abstract void uploadMeshes(CommandList commandList, RenderRegion region, Collection<ChunkBuildOutput> results);

    @Unique private RenderPipeline pipeline;


    @Redirect(method = "uploadMeshes(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadMeshes(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Lme/jellysquid/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V"))
    private void redirectUpload(RenderRegionManager instance, CommandList cmdList, RenderRegion pass, Collection<ChunkBuildOutput> uploadQueue) {
        if (Nvidium.IS_ENABLED) {
            uploadQueue.forEach(pipeline.sectionManager::uploadSetSection);
        } else {
            uploadMeshes(cmdList, pass, uploadQueue);
        }
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.pipeline = pipeline;
    }
}
