package me.cortex.nvidium.config;

import com.google.common.collect.ImmutableList;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ConfigGuiBuilder {
    private static final NvidiumConfigStore store = new NvidiumConfigStore();
    public static void addNvidiumGui(List<OptionPage> pages) {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.literal("Disable nvidium"))
                        .setTooltip(Text.literal("Used to disable nvidium (DOES NOT SAVE, WILL RE-ENABLE AFTER A RE-LAUNCH)"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> Nvidium.FORCE_DISABLE = value, opts -> Nvidium.FORCE_DISABLE)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).build());

        if (Nvidium.IS_COMPATIBLE && !Nvidium.IS_ENABLED && !Nvidium.FORCE_DISABLE) {
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
                        .setName(Text.translatable("nvidium.options.async_bfs.name"))
                        .setTooltip(Text.translatable("nvidium.options.async_bfs.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.async_bfs = value, opts -> opts.async_bfs)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
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
                ).add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("nvidium.options.render_fog.name"))
                        .setTooltip(Text.translatable("nvidium.options.render_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.render_fog = value, opts -> opts.render_fog)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).add(OptionImpl.createBuilder(TranslucencySortingLevel.class, store)
                        .setName(Text.translatable("nvidium.options.translucency_sorting.name"))
                        .setTooltip(Text.translatable("nvidium.options.translucency_sorting.tooltip"))
                        .setControl(
                                opts -> new CyclingControl<>(
                                        opts,
                                        TranslucencySortingLevel.class,
                                        new Text[]{
                                                Text.translatable("nvidium.options.translucency_sorting.none"),
                                                Text.translatable("nvidium.options.translucency_sorting.sections"),
                                                Text.translatable("nvidium.options.translucency_sorting.quads")
                                        }
                                )
                        )
                        .setBinding((opts, value) -> opts.translucency_sorting_level = value, opts -> opts.translucency_sorting_level)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        //Technically, only need to reload when going from NONE->SECTIONS
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).add(OptionImpl.createBuilder(StatisticsLoggingLevel.class, store)
                        .setName(Text.translatable("nvidium.options.statistics_level.name"))
                        .setTooltip(Text.translatable("nvidium.options.statistics_level.tooltip"))
                        .setControl(
                                opts -> new CyclingControl<>(
                                        opts,
                                        StatisticsLoggingLevel.class,
                                        new Text[]{
                                                Text.translatable("nvidium.options.statistics_level.none"),
                                                Text.translatable("nvidium.options.statistics_level.frustum"),
                                                Text.translatable("nvidium.options.statistics_level.regions"),
                                                Text.translatable("nvidium.options.statistics_level.sections"),
                                                Text.translatable("nvidium.options.statistics_level.quads")
                                        }
                                )
                        )
                        .setBinding((opts, value) -> opts.statistics_level = value, opts -> opts.statistics_level)
                        .setEnabled(Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(NvidiumOptionFlags.REQUIRES_SHADER_RELOAD)
                        .build()
                )
                .build());
        if (Nvidium.IS_COMPATIBLE) {
            pages.add(new OptionPage(Text.translatable("nvidium.options.pages.nvidium"), ImmutableList.copyOf(groups)));
        }
    }
}
