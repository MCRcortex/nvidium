package me.cortex.nvidium.sodiumCompat.mixin;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumConfigStore;
import me.jellysquid.mods.sodium.client.gl.arena.staging.MappedStagingBuffer;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;

@Mixin(value = SodiumGameOptionPages.class, remap = false)
public class MixinSodiumGameOptionPage {
    @Unique private static final NvidiumConfigStore store = new NvidiumConfigStore();

    @Inject(method = "advanced", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", shift = At.Shift.AFTER, ordinal = 1), locals = LocalCapture.CAPTURE_FAILSOFT)
    private static void addNvidiumOptions(CallbackInfoReturnable<OptionPage> cir, List<OptionGroup> groups) {
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.disable_chunk_unload.name"))
                        .setTooltip(Text.translatable("nvidium.options.disable_chunk_unload.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.disable_chunk_unloading = value, opts -> opts.disable_chunk_unloading)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .build());

    }
}
