package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Redirect(remap = true, method = "updateChunks", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;update(Lnet/minecraft/client/render/Camera;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;IZ)V"))
    private void disableChunkUpdates(RenderSectionManager instance, Camera camera, Viewport viewport, int frame, boolean spectator) {
        if (Nvidium.IS_ENABLED && Nvidium.config.disable_graph_update) {
            return;
        } else {
            instance.update(camera, viewport, frame, spectator);
        }
    }
}
