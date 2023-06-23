package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.render.BackgroundRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(BackgroundRenderer.class)

public class MixinBackgroundRenderer {
    @ModifyConstant(method = "applyFog", constant = @Constant(floatValue = 192.0F))
    private static float changeFog(float fog) {
        if (Nvidium.IS_ENABLED) {
            return 999999f;
        } else {
            return fog;
        }
    }
}
