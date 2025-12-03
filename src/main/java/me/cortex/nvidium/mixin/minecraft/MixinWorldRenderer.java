/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"), require = 0)
    private float redirectMax(float a, float b) {
        if (Nvidium.IS_ENABLED) {
            return a;
        }
        return Math.max(a, b);
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", ordinal = 1), index = 2)
    private float modifyRenderDistance(float original) {
        if (Nvidium.IS_ENABLED) {
            var dist = Nvidium.config.region_keep_distance * 16;
            if (dist == 32 * 16) {
                return original;
            } else if (dist == 256 * 16) {
                return 9999999;
            } else {
                return dist;
            }
        }
        return original;
    }
}