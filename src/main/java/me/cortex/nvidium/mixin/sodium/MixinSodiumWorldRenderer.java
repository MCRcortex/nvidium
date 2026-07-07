package me.cortex.nvidium.mixin.sodium;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.mixin.core.CommandEncoderAccessor;
import net.caffeinemc.mods.sodium.mixin.core.GlCommandEncoderAccessor;
import net.caffeinemc.mods.sodium.mixin.core.GpuDeviceAccessor;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements INvidiumWorldRendererGetter {
    @Shadow private RenderSectionManager renderSectionManager;
    @Unique private Viewport viewport;

    @Override
    public NvidiumWorldRenderer getRenderer() {
        if (Nvidium.IS_ENABLED) {
            return ((INvidiumWorldRendererGetter)renderSectionManager).getRenderer();
        } else {
            return null;
        }
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci) {
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(CommandList commandList, ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.CUTOUT) // Early exit, cutout will be rendered with SOLID
                return;

            RenderTarget target = pass.getTarget();
            GlStateManager._viewport(0, 0, target.getColorTexture().getWidth(0), target.getColorTexture().getHeight(0));
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, ((GlTexture) target.getColorTexture()).getFbo(((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).sodium$getBackend()).directStateAccess(), target.getDepthTexture()));
            ((GlCommandEncoderAccessor) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).sodium$getBackend()).sodium$applyPipelineState(pass.getPipeline());
            ((GlCommandEncoderAccessor) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).sodium$getBackend()).sodium$setLastProgram(null);

            if (pass == DefaultTerrainRenderPasses.SOLID) {
                this.getRenderer().renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                this.getRenderer().renderTranslucent(pass, terrainSampler);
            }
        }
    }
}
