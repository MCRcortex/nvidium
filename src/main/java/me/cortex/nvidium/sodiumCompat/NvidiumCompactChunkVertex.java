/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.sodiumCompat;


import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

public class NvidiumCompactChunkVertex implements ChunkVertexType {

    public static final int STRIDE = 16;
    public static final NvidiumCompactChunkVertex INSTANCE = new NvidiumCompactChunkVertex();

    private static final int POSITION_MAX_VALUE = 65536;
    public static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;
    private static final float TEXTURE_SCALE = (1.0f / TEXTURE_MAX_VALUE);


    @Override
    public GlVertexFormat getVertexFormat() {
        return null;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return new ChunkVertexEncoder() {
            @Override
            public long write(long l, int i, Vertex[] vertices, int i1) {
                return 0;
            }

            public long write(long ptr, Material material, ChunkVertexEncoder.Vertex vertex, int sectionIndex) {
                int light = compactLight(vertex.light);

                MemoryUtil.memPutInt(ptr + 0, (encodePosition(vertex.x) << 0) | (encodePosition(vertex.y) << 16));
                MemoryUtil.memPutInt(ptr + 4, (encodePosition(vertex.z) << 0) | (encodeDrawParameters(material) << 16) | ((light & 0xFF) << 24));
                MemoryUtil.memPutInt(ptr + 8, (encodeColor(vertex.color) << 0) | (((light >> 8) & 0xFF) << 24));
                MemoryUtil.memPutInt(ptr + 12, encodeTexture(vertex.u, vertex.v));

                return ptr + STRIDE;
            }
        };
    }

    private static int compactLight(int light) {
        int sky = Mth.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = Mth.clamp((light >>> 0) & 0xFF, 8, 248);

        return (block << 0) | (sky << 8);
    }

    private static int encodePosition(float v) {
        return (int) ((MODEL_ORIGIN + v) * MODEL_SCALE_INV);
    }

    private static int encodeDrawParameters(Material material) {
        return ((material.bits() & 0xFF) << 0);
    }

    private static int encodeColor(int color) {
        var brightness = ColorU8.byteToNormalizedFloat(ColorABGR.unpackAlpha(color));

        int r = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackRed(color)) * brightness);
        int g = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackGreen(color)) * brightness);
        int b = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackBlue(color)) * brightness);

        return ColorABGR.pack(r, g, b, 0x00);
    }

    private static int encodeTexture(float u, float v) {
        return ((Math.round(u * TEXTURE_MAX_VALUE) & 0xFFFF) << 0) |
                ((Math.round(v * TEXTURE_MAX_VALUE) & 0xFFFF) << 16);
    }
}