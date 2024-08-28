package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.sodiumCompat.IRepackagedResult;
import me.cortex.nvidium.sodiumCompat.RepackagedSectionOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildOutput implements IRepackagedResult {
    @Unique private RepackagedSectionOutput repackagedSectionOutput;

    public RepackagedSectionOutput getOutput() {
        return repackagedSectionOutput;
    }

    public void set(RepackagedSectionOutput output) {
        repackagedSectionOutput = output;
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cleanup(CallbackInfo ci) {
        if (repackagedSectionOutput != null) {
            repackagedSectionOutput.delete();
        }
    }
}
