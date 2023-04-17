package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Inject(method = "getMemoryDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(CallbackInfoReturnable<Collection<String>> cir) {
        if (Nvidium.IS_ENABLED) {
            var debugStrings = new ArrayList<String>();
            Nvidium.pipeline.addDebugInfo(debugStrings);
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }
}
