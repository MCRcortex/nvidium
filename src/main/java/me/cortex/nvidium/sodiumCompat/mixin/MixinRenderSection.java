package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkGraphicsState;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Mixin(value = RenderSection.class, remap = false)
public class MixinRenderSection {
    @Redirect(method = "isEmpty", at = @At(value = "INVOKE", target = "Ljava/util/Map;isEmpty()Z"))
    private boolean isEmpty(Map<BlockRenderPass, ChunkGraphicsState> instance) {
        if (Nvidium.IS_ENABLED) {
            return false;
        }
        return instance.isEmpty();
    }
}