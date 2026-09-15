package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.NvidiumConfig;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    @ModifyVariable(
            method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
            at = @At(value = "STORE"),
            ordinal = 2
    )
    private float modifyFogRD(float viewDistance) {
        if (Nvidium.IS_ENABLED && Nvidium.config.gpuKeepChunks() > NvidiumConfig.VANILLA_KEEP_DISTANCE) {
            return Math.max(viewDistance, Nvidium.config.gpuKeepChunks() * 16);
        }
        return viewDistance;
    }

    @ModifyArg(
            method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
            index = 7
    )
    private float clampSkyEnd(float skyEnd) {
        return Mth.clamp(skyEnd, 2 * 16, 32 * 16);
    }
}
