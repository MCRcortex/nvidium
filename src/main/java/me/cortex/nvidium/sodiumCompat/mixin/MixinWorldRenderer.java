package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.NvidiumConfig;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float redirectMax(float a, float b) {
        return a;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getViewDistance()F"))
    private float changeRD(GameRenderer instance) {
        float viewDistance = instance.getViewDistance();
        var dist = Nvidium.config.region_keep_distance*16;
        return dist==32*16?viewDistance:(dist==256*16?9999999:dist);
    }
}
