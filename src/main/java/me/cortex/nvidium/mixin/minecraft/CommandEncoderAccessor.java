package me.cortex.nvidium.mixin.minecraft;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {
    @Accessor("backend")
    CommandEncoderBackend nvidium$getCommandEncoderBackend();
}
