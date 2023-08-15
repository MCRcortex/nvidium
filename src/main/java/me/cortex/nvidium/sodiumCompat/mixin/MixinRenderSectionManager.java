package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineGetter;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineSetter;
import me.cortex.nvidium.sodiumCompat.IrisCheck;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager implements IRenderPipelineGetter {
    @Shadow @Final private RenderRegionManager regions;
    @Unique private RenderPipeline pipeline;
    @Unique private Viewport viewport;


    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(ClientWorld world, int renderDistance, CommandList commandList, CallbackInfo ci) {
        Nvidium.IS_ENABLED = Nvidium.IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable();
        if (Nvidium.IS_ENABLED) {
            if (pipeline != null)
                throw new IllegalStateException("Cannot have multiple pipelines");
            pipeline = new RenderPipeline();
            ((IRenderPipelineSetter)regions).setPipeline(pipeline);
        }
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            if (pipeline == null)
                throw new IllegalStateException("Pipeline already destroyed");
            ((IRenderPipelineSetter)regions).setPipeline(null);
            pipeline.delete();
            pipeline = null;
        }
    }

    @Redirect(method = "onSectionRemoved", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;delete()V"))
    private void deleteSection(RenderSection section) {
        if (Nvidium.IS_ENABLED) {
            if (Nvidium.config.region_keep_distance == 32) {
                pipeline.sectionManager.deleteSection(section);
            }
        }
        section.delete();
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void trackViewport(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.SOLID) {
                pipeline.renderFrame(viewport, matrices, x, y, z);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                pipeline.renderTranslucent();
            }
        }
    }

    @Inject(method = "getDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(CallbackInfoReturnable<Collection<String>> cir) {
        if (Nvidium.IS_ENABLED) {
            var debugStrings = new ArrayList<String>();
            pipeline.addDebugInfo(debugStrings);
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }

    @Override
    public RenderPipeline getPipeline() {
        return pipeline;
    }
}
