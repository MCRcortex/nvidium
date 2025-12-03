/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = {"net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobQueue"},remap = false)
public class MixinChunkJobQueue {
    @Redirect(method = "shutdown", at = @At(value = "INVOKE", target = "Ljava/lang/Runtime;availableProcessors()I"))
    private int returnAlot(Runtime instance) {
        return Integer.MAX_VALUE>>1;
    }
}
