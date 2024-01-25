package me.cortex.nvidium.entities;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;

public class EncodedRenderLayer {

    public EncodedRenderLayer(RenderLayer.MultiPhase layer) {
        for (var phase : layer.phases.phases) {
            //if (phase instanceof RenderPhase.ShaderProgram program) {
            //} else if (phase instanceof RenderPhase.TextureBase program) {
            //} else if (phase instanceof RenderPhase.TextureBase program) {
            //} else {
            //    throw new IllegalStateException("Unknown phase: " + phase);
            //}
        }
    }

    //Returns if the render layers can be drawn in the same call, this will depend
    // on the capabilities of the render system
    public boolean canBatchWith(EncodedRenderLayer other) {
        return false;
    }
}
