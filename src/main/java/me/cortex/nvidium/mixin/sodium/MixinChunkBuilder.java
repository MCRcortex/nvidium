package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = ChunkBuilder.class, remap = false)
public class MixinChunkBuilder {
    @Redirect(method = "getSchedulingBudget", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int moreSchedulingBudget(List<Thread> threads) {
        int budget = threads.size();
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            budget *= 3;
        }
        return budget;
    }
}
