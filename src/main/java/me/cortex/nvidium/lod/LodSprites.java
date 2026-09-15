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
        TextureAtlasSprite sprite = null;
        try {
            sprite = atlas.getSprite(Identifier.withDefaultNamespace("block/white_concrete"));
        } catch (Throwable ignored) {
        }
        if (sprite == null) {
            u0 = 0.5f;
            v0 = 0.5f;
            u1 = 0.5f;
            v1 = 0.5f;
            return;
        }
        u0 = sprite.getU(0.5f);
        v0 = sprite.getV(0.5f);
        u1 = u0;
        v1 = v0;
    }
}
