/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = ChunkBuilder.class, remap = false)
public class MixinChunkBuilder {
    @ModifyArg(
            method = "getHighEffortSchedulingBudget",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0)
    )
    private int modifyHighEffortBudget(int original) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            return original * 3;
        }
        return original;
    }

    @ModifyArg(
            method = "getLowEffortSchedulingBudget",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0)
    )
    private int modifyLowEffortBudget(int original) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            return original * 3;
        }
        return original;
    }
}