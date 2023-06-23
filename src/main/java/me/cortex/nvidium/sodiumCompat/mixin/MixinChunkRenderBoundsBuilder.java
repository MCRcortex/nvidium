package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//This is to fix recovery of the original chunk bounds and data, is it cursed? yes, does it work? also yes
@Mixin(value = ChunkRenderBounds.Builder.class, remap = false)
public abstract class MixinChunkRenderBoundsBuilder {
    @Shadow private int x;

    @Shadow private int y;

    @Shadow private int z;

    @Inject(method = "build", at = @At("HEAD"), cancellable = true)
    private void redirectBuilder(ChunkSectionPos origin, CallbackInfoReturnable<ChunkRenderBounds> cir) {
        if (Nvidium.IS_ENABLED) {
            if ((this.x | this.y | this.z) == 0) {
                cir.setReturnValue(new ChunkRenderBounds(origin));
                cir.cancel();
            } else {
                int x1 = origin.getMinX() + leftBound(this.x);
                int x2 = origin.getMinX() + rightBound(this.x);
                int y1 = origin.getMinY() + leftBound(this.y);
                int y2 = origin.getMinY() + rightBound(this.y);
                int z1 = origin.getMinZ() + leftBound(this.z);
                int z2 = origin.getMinZ() + rightBound(this.z);
                cir.setReturnValue(new ChunkRenderBounds(
                        (float)x1 - 0.5F,
                        (float)y1 - 0.5F,
                        (float)z1 - 0.5F,
                        (float)x2 + 0.5F,
                        (float)y2 + 0.5F,
                        (float)z2 + 0.5F
                ));
                cir.cancel();
            }
        }
    }

    //Stupid mixin making me duplicate code
    private static int leftBound(int i) {
        return Integer.numberOfTrailingZeros(i);
    }

    private static int rightBound(int i) {
        return 32 - Integer.numberOfLeadingZeros(i);
    }
}
