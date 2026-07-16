package me.cortex.nvidium.mixin.sodium;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements INvidiumWorldRendererGetter {
    @Shadow
    private RenderSectionManager renderSectionManager;
    @Unique
    private Viewport viewport;

    @Override
    public NvidiumWorldRenderer getRenderer() {
        if (Nvidium.IS_ENABLED) {
            return ((INvidiumWorldRendererGetter) renderSectionManager).getRenderer();
        } else {
            return null;
        }
    }

    @Inject(method = "setupTerrain", at = @At(value = "HEAD"))
    public void trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci) {
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At(value = "HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.CUTOUT) // Early exit, cutout will be rendered with SOLID
                return;

            try (RenderPass ignored = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            () -> "Nvidium Terrain",
                            pass.getTarget().getColorTextureView(),
                            Optional.empty(),
                            pass.getTarget().getDepthTextureView(),
                            OptionalDouble.empty()
                    )) {
                GlStateManager._disableScissorTest();
                GlStateManager._enableCull();
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GL33C.GL_GEQUAL); // reverse-Z
                GlStateManager._colorMask(0, 15);
                GlStateManager._depthMask(true);

                if (pass == DefaultTerrainRenderPasses.SOLID) {
                    GlStateManager._disableBlend(0);

                    this.getRenderer().renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);
                } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                    GlStateManager._enableBlend(0);
                    GlStateManager._blendFuncSeparate(
                            GL33C.GL_SRC_ALPHA,
                            GL33C.GL_ONE_MINUS_SRC_ALPHA,
                            GL33C.GL_ONE,
                            GL33C.GL_ONE_MINUS_SRC_ALPHA
                    );

                    this.getRenderer().renderTranslucent(pass, terrainSampler);
                }
                GlStateManager._disableBlend(0);
            }
        }
    }
}
