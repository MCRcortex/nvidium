package me.cortex.nvidium.mixin.minecraft;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendCommandEncoder.class)
public interface FrontendCommandEncoderAccessor {
    @Accessor("backend")
    CommandEncoderBackend nvidium$getBackend();
}
