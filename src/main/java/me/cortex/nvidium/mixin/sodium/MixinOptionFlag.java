/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;

import net.caffeinemc.mods.sodium.client.gui.options.OptionFlag;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = OptionFlag.class, remap = false)
public class MixinOptionFlag {
    @Shadow
    @Final
    @Mutable
    private static OptionFlag[] $VALUES = ArrayUtils.addAll(MixinOptionFlag.$VALUES, NvidiumOptionFlags.REQUIRES_SHADER_RELOAD);

    public MixinOptionFlag() {
    }

    @Invoker("<init>")
    public static OptionFlag optionFlagCreator(String internalName, int internalId) {
        throw new AssertionError();
    }

    static {
        NvidiumOptionFlags.REQUIRES_SHADER_RELOAD = optionFlagCreator("REQUIRES_SHADER_RELOAD", $VALUES.length);
    }
}
