package me.cortex.nvidium.sodiumCompat.mixin;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapTextureManager.class)
public interface LightMapAccessor {
    @Accessor()
    NativeImageBackedTexture getTexture();
}
