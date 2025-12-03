/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererSetter;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class MixinRenderRegionManager implements INvidiumWorldRendererSetter {

    @Unique
    private NvidiumWorldRenderer renderer;


    @Inject(method = "uploadResults", at = @At("HEAD"), cancellable = true, remap = false)
    private void onUploadResults(CommandList commandList, Collection<BuilderTaskOutput> results, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && renderer != null) {
            for (BuilderTaskOutput result : results) {

                if (result instanceof ChunkBuildOutput buildOutput) {
                    renderer.uploadBuildResult(buildOutput);
                }
            }
            ci.cancel();
        }
    }

    @Override
    public void setWorldRenderer(NvidiumWorldRenderer renderer) {
        this.renderer = renderer;
    }
}