/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.sodiumCompat;

import net.neoforged.fml.ModList;

public class IrisCheck {
    public static final boolean IRIS_LOADED = ModList.get().isLoaded("iris");


    public static boolean checkIrisShouldDisable() {
        return IRIS_LOADED;
    }
}