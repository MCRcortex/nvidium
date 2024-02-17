package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float redirectMax(float a, float b) {
        if (Nvidium.IS_ENABLED) {
            return a;
        }
        return Math.max(a, b);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getViewDistance()F"))
    private float changeRD(GameRenderer instance) {
        float viewDistance = instance.getViewDistance();
        if (Nvidium.IS_ENABLED) {
            var dist = Nvidium.config.region_keep_distance * 16;
            return dist == 32 * 16 ? viewDistance : (dist == 256 * 16 ? 9999999 : dist);
        }
        return viewDistance;
    }

    @ModifyArg(method = "method_37365", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/BackgroundRenderer;applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;FZF)V"), index = 2)
    private static float clampViewDistance(float viewDistance) {
        return MathHelper.clamp(viewDistance, 2 * 16, 32 * 16);
    }
}
