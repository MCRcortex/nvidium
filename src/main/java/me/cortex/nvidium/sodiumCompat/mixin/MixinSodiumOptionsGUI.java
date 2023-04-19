package me.cortex.nvidium.sodiumCompat.mixin;

import com.google.common.collect.ImmutableList;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.NvidiumConfigStore;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinSodiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;
    @Unique private static final NvidiumConfigStore store = new NvidiumConfigStore();

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 3, shift = At.Shift.AFTER))
    private void addNvidiumOptions(Screen prevScreen, CallbackInfo ci) {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.enable_mipping.name"))
                        .setTooltip(Text.translatable("nvidium.options.enable_mipping.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.mips_enabled = value, opts -> opts.mips_enabled)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.disable_chunk_unload.name"))
                        .setTooltip(Text.translatable("nvidium.options.disable_chunk_unload.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.disable_chunk_unloading = value, opts -> opts.disable_chunk_unloading)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("nvidium.options.extra_render_distance.name"))
                        .setTooltip(Text.translatable("nvidium.options.extra_render_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.extra_rd = value, opts -> opts.extra_rd)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("nvidium.options.fallback_allocation_size.name"))
                        .setTooltip(Text.translatable("nvidium.options.fallback_allocation_size.tooltip"))
                        .setControl(option -> new SliderControl(option, 2048, 12000, 1, ControlValueFormatter.translateVariable("nvidium.options.mb")))
                        .setImpact(OptionImpact.LOW)
                        .setEnabled(Nvidium.IS_ENABLED && !Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER)
                        .setBinding((opts, value) -> opts.fallback_allocation_size = value, opts -> opts.fallback_allocation_size)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .build());
        this.pages.add(new OptionPage(Text.translatable("nvidium.options.pages.nvidium"), ImmutableList.copyOf(groups)));
    }
}
