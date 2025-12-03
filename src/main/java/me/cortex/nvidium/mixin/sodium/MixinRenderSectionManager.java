/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.sodiumCompat.*;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager implements INvidiumWorldRendererGetter {
    @Shadow @Final private RenderRegionManager regions;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow @Final private int renderDistance;
    @Shadow @Final private ClientLevel level;

    @Unique private NvidiumWorldRenderer renderer;

    @Unique
    private static void updateNvidiumIsEnabled() {
        Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(ClientLevel world, int renderDistance, CommandList commandList, CallbackInfo ci) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            if (renderer != null)
                throw new IllegalStateException("Cannot have multiple world renderers");

            renderer = new NvidiumWorldRenderer(null);
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(renderer);
        }
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"), index = 1)
    private ChunkVertexType modifyVertexType(ChunkVertexType vertexType) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            return NvidiumCompactChunkVertex.INSTANCE;
        }
        return vertexType;
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            if (renderer == null)
                throw new IllegalStateException("Pipeline already destroyed");
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(null);
            renderer.delete();
            renderer = null;
        }
    }

    @Redirect(method = "onSectionRemoved", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;delete()V"))
    private void deleteSection(RenderSection section) {
        if (Nvidium.IS_ENABLED) {
            if (Nvidium.config.region_keep_distance == 32) {
                renderer.deleteSection(section);
            }
        }
        section.delete();
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    private void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            pass.startDrawing();
            if (pass == DefaultTerrainRenderPasses.SOLID) {
                if (renderer != null) {
                    renderer.renderFrame(matrices, x, y, z);
                }
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                if (renderer != null) {
                    renderer.renderTranslucent();
                }
            }
            pass.endDrawing();
        }
    }

    @Inject(method = "getDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(CallbackInfoReturnable<Collection<String>> cir) {
        if (Nvidium.IS_ENABLED) {
            var debugStrings = new ArrayList<String>();
            if (renderer != null) {
                renderer.addDebugInfo(debugStrings);
            }
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }

    @Override
    public NvidiumWorldRenderer getRenderer() {
        return renderer;
    }

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
    private void redirectTerrainRenderList(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            ci.cancel();
        }
    }

    @Unique
    private boolean isSectionVisibleBfs(RenderSection section) {
        if (renderer == null) return false;
        int delta = Math.abs(section.getLastVisibleFrame() - renderer.getAsyncFrameId());
        return delta <= 1;
    }

    @Inject(method = "isSectionVisible", at = @At("RETURN"), cancellable = true)
    private void redirectIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs && !cir.getReturnValueZ()) {
            RenderSection render = sectionByPosition.get(SectionPos.asLong(x, y, z));
            if (render != null) {
                cir.setReturnValue(isSectionVisibleBfs(render));
            }
        }
    }

    @Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
    private void injectVisibilityCount(CallbackInfoReturnable<Integer> cir) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            if (renderer != null) {
                cir.setReturnValue(renderer.getAsyncBfsVisibilityCount());
            }
        }
    }
}