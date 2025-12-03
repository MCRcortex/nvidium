/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.BlockDestructionProgress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedSet;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements INvidiumWorldRendererGetter {

    @Shadow
    private RenderSectionManager renderSectionManager;


    @Inject(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;needsUpdate()Z", shift = At.Shift.BEFORE))
    private void injectTerrainSetup(Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            ((INvidiumWorldRendererGetter)renderSectionManager).getRenderer().update(camera, viewport, spectator);
        }
    }

    @Inject(method = "renderBlockEntities",
            at = @At("HEAD"),
            cancellable = true)
    private void overrideEntityRenderer(PoseStack poseStack,
                                        RenderBuffers bufferBuilders,
                                        Long2ObjectMap<SortedSet<BlockDestructionProgress>> blockBreakingProgressions,
                                        float tickDelta,
                                        MultiBufferSource.BufferSource immediate,
                                        double x, double y, double z,
                                        BlockEntityRenderDispatcher blockEntityRenderer,
                                        CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            NvidiumWorldRenderer renderer = ((INvidiumWorldRendererGetter) renderSectionManager).getRenderer();
            if (renderer == null) {
                return;
            }

            ci.cancel();

            var sectionsWithEntities = renderer.getSectionsWithEntities();
            if (sectionsWithEntities == null) {
                return;
            }

            for (var section : sectionsWithEntities) {
                if (section.isDisposed()) {
                    continue;
                }

                var culledBlockEntities = section.getCulledBlockEntities();
                if (culledBlockEntities == null) {
                    continue;
                }

                for (BlockEntity entity : culledBlockEntities) {
                    nvidium$renderBlockEntity(poseStack, bufferBuilders, blockBreakingProgressions,
                            tickDelta, immediate, x, y, z, blockEntityRenderer, entity);
                }
            }
        }
    }

    @Unique
    private static void nvidium$renderBlockEntity(PoseStack poseStack,
                                                  RenderBuffers bufferBuilders,
                                                  Long2ObjectMap<SortedSet<BlockDestructionProgress>> blockBreakingProgressions,
                                                  float tickDelta,
                                                  MultiBufferSource.BufferSource immediate,
                                                  double x, double y, double z,
                                                  BlockEntityRenderDispatcher dispatcher,
                                                  BlockEntity entity) {
        var pos = entity.getBlockPos();

        poseStack.pushPose();
        poseStack.translate(pos.getX() - x, pos.getY() - y, pos.getZ() - z);


        dispatcher.render(entity, tickDelta, poseStack, immediate);

        poseStack.popPose();
    }

    @Override
    public NvidiumWorldRenderer getRenderer() {
        if (Nvidium.IS_ENABLED && renderSectionManager != null) {
            return ((INvidiumWorldRendererGetter) renderSectionManager).getRenderer();
        }
        return null;
    }
}