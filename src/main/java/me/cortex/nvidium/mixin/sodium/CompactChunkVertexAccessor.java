package me.cortex.nvidium.mixin.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CompactChunkVertex.class, remap = false)
public interface CompactChunkVertexAccessor {
    @Accessor
    static int getTEXTURE_MAX_VALUE() {
        throw new IllegalStateException();
    }
}
