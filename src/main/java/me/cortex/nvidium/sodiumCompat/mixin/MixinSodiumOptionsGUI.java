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

import java.util.ArrayList;
import java.util.List;

@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinSodiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;
    @Unique private static final NvidiumConfigStore store = new NvidiumConfigStore();

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 3, shift = At.Shift.AFTER))
    private void addNvidiumOptions(Screen prevScreen, CallbackInfo ci) {
        List<OptionGroup> groups = new ArrayList<>();
        if (Nvidium.IS_COMPATIBLE && !Nvidium.IS_ENABLED) {
            groups.add(OptionGroup.createBuilder()
                    .add(OptionImpl.createBuilder(boolean.class, store)
                            .setName(Text.literal("Nvidium disabled due to shaders being loaded"))
                            .setTooltip(Text.literal("Nvidium disabled due to shaders being loaded"))
                            .setControl(TickBoxControl::new)
                            .setImpact(OptionImpact.VARIES)
                            .setBinding((opts, value) -> {}, opts -> false)
                            .setFlags()
                            .build()
                    ).build());
        }
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("nvidium.options.region_keep_distance.name"))
                        .setTooltip(Text.translatable("nvidium.options.region_keep_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 32, 256, 1, x->Text.literal(x==32?"Vanilla":(x==256?"Keep All":x+" chunks"))))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.region_keep_distance = value, opts -> opts.region_keep_distance)
                        .setFlags()
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.enable_temporal_coherence.name"))
                        .setTooltip(Text.translatable("nvidium.options.enable_temporal_coherence.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.enable_temporal_coherence = value, opts -> opts.enable_temporal_coherence)
                        .setFlags()
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.automatic_memory_limit.name"))
                        .setTooltip(Text.translatable("nvidium.options.automatic_memory_limit.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.automatic_memory = value, opts -> opts.automatic_memory)
                        .setFlags()
                        .build())
               .add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("nvidium.options.max_gpu_memory.name"))
                        .setTooltip(Text.translatable("nvidium.options.max_gpu_memory.tooltip"))
                        .setControl(option -> new SliderControl(option, 2048, 32768, 512, ControlValueFormatter.translateVariable("nvidium.options.mb")))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Nvidium.IS_ENABLED && !Nvidium.config.automatic_memory)
                        .setBinding((opts, value) -> opts.max_geometry_memory = value, opts -> opts.max_geometry_memory)
                        .setFlags(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?new OptionFlag[0]:new OptionFlag[]{OptionFlag.REQUIRES_RENDERER_RELOAD})
                        .build()
                )/*
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.disable_graph_update.name"))
                        .setTooltip(Text.translatable("nvidium.options.disable_graph_update.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.disable_graph_update = value, opts -> opts.disable_graph_update)
                        .setFlags()
                        .build()
                )*/
                .build());
        this.pages.add(new OptionPage(Text.translatable("nvidium.options.pages.nvidium"), ImmutableList.copyOf(groups)));
    }
}
