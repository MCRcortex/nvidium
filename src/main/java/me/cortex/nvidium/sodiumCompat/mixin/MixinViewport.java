package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.sodiumCompat.IViewportTest;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = Viewport.class, remap = false)
public class MixinViewport implements IViewportTest {
    @Shadow @Final private CameraTransform transform;

    @Shadow @Final private Frustum frustum;

    public boolean isBoxVisible(int intX, int intY, int intZ, float size_x, float size_y, float size_z) {
        float floatX = (float)(intX - this.transform.intX) - this.transform.fracX;
        float floatY = (float)(intY - this.transform.intY) - this.transform.fracY;
        float floatZ = (float)(intZ - this.transform.intZ) - this.transform.fracZ;
        return this.frustum.testAab(floatX, floatY, floatZ, floatX + size_x, floatY + size_y, floatZ + size_z);
    }
}
