package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineGetter;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineSetter;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPassManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager implements IRenderPipelineGetter {
    @Shadow private Frustum frustum;
    @Shadow @Final private RenderRegionManager regions;
    @Unique private RenderPipeline pipeline;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(SodiumWorldRenderer worldRenderer, BlockRenderPassManager renderPassManager, ClientWorld world, int renderDistance, CommandList commandList, CallbackInfo ci) {
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

    @Inject(method = "unloadSection", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;delete()V", shift = At.Shift.BEFORE), locals = LocalCapture.CAPTURE_FAILHARD)
    private void deleteSection(int x, int y, int z, CallbackInfoReturnable<Boolean> cir, RenderSection chunk) {
        if (Nvidium.IS_ENABLED) {
            if (Nvidium.config.region_keep_distance == 32) {
                pipeline.sectionManager.deleteSection(chunk);
            }
        }
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, BlockRenderPass pass, double x, double y, double z, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == BlockRenderPass.SOLID) {
                pipeline.renderFrame(frustum, matrices, x, y, z);
            } else if (pass == BlockRenderPass.TRANSLUCENT) {
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
