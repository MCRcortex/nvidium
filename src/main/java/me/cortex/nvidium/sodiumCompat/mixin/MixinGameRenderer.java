package me.cortex.nvidium.sodiumCompat.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Overwrite
    public float method_32796() {
        return 16*5000;
    }
}
