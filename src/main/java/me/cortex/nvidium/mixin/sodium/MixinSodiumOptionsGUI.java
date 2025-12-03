/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.config.ConfigGuiBuilder;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;
import net.caffeinemc.mods.sodium.client.gui.SodiumGameOptions;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.*;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

@Mixin(value = SodiumOptionsGUI.class, remap = false)
public abstract class MixinSodiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 3, shift = At.Shift.AFTER))
    private void addNvidiumOptions(Screen prevScreen, CallbackInfo ci) {
        ConfigGuiBuilder.addNvidiumGui(pages);
    }

    @Inject(method = "applyChanges", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getWindow()Lcom/mojang/blaze3d/platform/Window;"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void applyShaderReload(CallbackInfo ci,
                                   HashSet<OptionStorage<?>> dirtyStorages,
                                   EnumSet<OptionFlag> flags,
                                   Minecraft client) {
        if (client.level != null) {
            if (flags.contains(NvidiumOptionFlags.REQUIRES_SHADER_RELOAD)) {
                SodiumWorldRenderer swr = SodiumWorldRenderer.instanceNullable();
                if (swr != null) {

                    try {

                        java.lang.reflect.Field field = swr.getClass().getDeclaredField("renderSectionManager");
                        field.setAccessible(true);
                        Object renderSectionManager = field.get(swr);

                        if (renderSectionManager instanceof INvidiumWorldRendererGetter getter) {
                            NvidiumWorldRenderer pipeline = getter.getRenderer();
                            if (pipeline != null) {
                                pipeline.reloadShaders();
                            }
                        }
                    } catch (Exception e) {

                        System.err.println("Failed to access renderSectionManager: " + e.getMessage());
                    }
                }
            }
        }
    }
}