/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.renderers;

import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.ResourceLocation;

import static me.cortex.nvidium.gl.shader.ShaderType.FRAGMENT;
import static me.cortex.nvidium.gl.shader.ShaderType.MESH;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;

public class RegionRasterizer extends Phase {
    private final Shader shader = Shader.make()
            .addSource(MESH, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "occlusion/region_raster/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "occlusion/region_raster/fragment.frag")))
            .compile();

    public void raster(int regionCount) {
        shader.bind();
        glDrawMeshTasksNV(0, regionCount);
    }

    public void delete() {
        shader.delete();
    }
}