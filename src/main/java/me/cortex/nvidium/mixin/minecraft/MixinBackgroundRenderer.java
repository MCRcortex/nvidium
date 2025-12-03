/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(FogRenderer.class)
public class MixinBackgroundRenderer {

    @ModifyConstant(method = "setupFog", constant = @Constant(floatValue = 192.0F))
    private static float changeFog(float fog) {
        if (Nvidium.IS_ENABLED) {
            return 9999999f;
        } else {
            return fog;
        }
    }
}
