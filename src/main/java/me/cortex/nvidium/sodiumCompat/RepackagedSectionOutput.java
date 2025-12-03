/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.sodiumCompat;

import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.joml.Vector3i;

//Computed on the build thread instead of the render thread saving alot of 1% lows
public record RepackagedSectionOutput(int quads,
                                      NativeBuffer geometry,
                                      short[] offsets,
                                      Vector3i min,
                                      Vector3i size) {
    public void delete() {
        geometry.free();
    }
}
