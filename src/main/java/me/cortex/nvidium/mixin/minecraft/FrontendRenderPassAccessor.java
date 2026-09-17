package me.cortex.nvidium.mixin.minecraft;

import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;

@Mixin(FrontendRenderPass.class)
public interface FrontendRenderPassAccessor {
    @Accessor("uniforms")
    HashMap<String, Object> nvidium$getUniforms();
}
