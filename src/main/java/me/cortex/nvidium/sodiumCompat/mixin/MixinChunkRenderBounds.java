package me.cortex.nvidium.sodiumCompat.mixin;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ChunkRenderBounds.Builder.class, remap = false)
public class MixinChunkRenderBounds {
    @Shadow private float minY;

    @Shadow private float maxY;

    @Shadow private float minX;

    @Shadow private float maxX;

    @Shadow private float minZ;

    @Shadow private float maxZ;

    @Overwrite
    public void add(float x, float y, float z, ModelQuadFacing facing) {
        this.minY = Math.min(this.minY, y);
        this.maxY = Math.max(this.maxY, y);
        this.minX = Math.min(this.minX, x);
        this.maxX = Math.max(this.maxX, x);
        this.minZ = Math.min(this.minZ, z);
        this.maxZ = Math.max(this.maxZ, z);
    }
}