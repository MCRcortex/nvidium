package me.cortex.nvidium.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

public final class LodSprites {
    public static volatile float u0 = 0f;
    public static volatile float v0 = 0f;
    public static volatile float u1 = 1f;
    public static volatile float v1 = 1f;

    private LodSprites() {}

    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getTextureManager() == null) {
            return;
        }
        var texture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(texture instanceof TextureAtlas atlas)) {
            return;
        }
        TextureAtlasSprite sprite = findOpaqueSprite(atlas);
        if (sprite == null) {
            return;
        }
        // Mid-texel of an opaque tile. Edge UVs bleed into neighbouring atlas
        // sprites (leaves/glass) and the terrain shader then draws black.
        u0 = (sprite.getU0() + sprite.getU1()) * 0.5f;
        v0 = (sprite.getV0() + sprite.getV1()) * 0.5f;
        u1 = u0;
        v1 = v0;
    }

    private static TextureAtlasSprite findOpaqueSprite(TextureAtlas atlas) {
        String[] names = {"block/stone", "block/dirt", "block/white_concrete", "block/oak_planks"};
        for (String name : names) {
            try {
                TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace(name));
                if (sprite != null && sprite.atlasLocation() != null) {
                    return sprite;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
