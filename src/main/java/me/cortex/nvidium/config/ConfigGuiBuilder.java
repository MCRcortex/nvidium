package me.cortex.nvidium.config;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.client.gui.options.*;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatterImpls;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ConfigGuiBuilder implements ConfigEntryPoint {
    private static final NvidiumConfigStore store = new NvidiumConfigStore();
    private final StorageEventHandler saveConfig = store::save;
    private final StorageEventHandler noSave = () -> {};

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var nvidiumOptionPage = builder.createOptionPage()
                .setName(Component.literal("Nvidium"));

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:disable_nvidium"))
                        .setName(Component.literal("Disable nvidium"))
                        .setTooltip(Component.literal("Used to disable nvidium (DOES NOT SAVE, WILL RE-ENABLE AFTER A RE-LAUNCH)"))
                        .setDefaultValue(false)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((value) -> Nvidium.FORCE_DISABLE = value, () -> Nvidium.FORCE_DISABLE)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.noSave)
        );

        if (Nvidium.IS_COMPATIBLE && !Nvidium.IS_ENABLED && !Nvidium.FORCE_DISABLE) {
            nvidiumOptionPage.addOption(
                    builder.createBooleanOption(Identifier.parse("nvidium:force_disabled_nvidium"))
                            .setName(Component.literal("Nvidium disabled due to shaders being loaded"))
                            .setTooltip(Component.literal("Nvidium disabled due to shaders being loaded"))
                            .setImpact(OptionImpact.VARIES)
                            .setDefaultValue(true)
                            .setBinding((v) -> {}, () -> false)
                            .setStorageHandler(this.noSave)
            );
        }

        nvidiumOptionPage.addOption(
                builder.createIntegerOption(Identifier.parse("nvidium:region_keep_distace"))
                        .setName(Component.translatable("nvidium.options.region_keep_distance.name"))
                        .setTooltip(Component.translatable("nvidium.options.region_keep_distance.tooltip"))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setBinding(v -> store.getData().region_keep_distance = v, () -> store.getData().region_keep_distance)
                        .setRange(NvidiumConfig.VANILLA_KEEP_DISTANCE, NvidiumConfig.KEEP_ALL_DISTANCE, 1)
                        .setDefaultValue(NvidiumConfig.KEEP_ALL_DISTANCE)
                        .setValueFormatter(x -> Component.literal(
                                x >= NvidiumConfig.KEEP_ALL_DISTANCE
                                        ? "Keep All"
                                        : (x <= NvidiumConfig.VANILLA_KEEP_DISTANCE
                                        ? x + " chunks (vanilla)"
                                        : x + " chunks")))
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:enable_disk_persistence"))
                        .setName(Component.translatable("nvidium.options.enable_disk_persistence.name"))
                        .setTooltip(Component.translatable("nvidium.options.enable_disk_persistence.tooltip"))
                        .setDefaultValue(true)
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setBinding(v -> store.getData().enable_disk_persistence = v, () -> store.getData().diskPersistence())
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:enable_lod"))
                        .setName(Component.translatable("nvidium.options.enable_lod.name"))
                        .setTooltip(Component.translatable("nvidium.options.enable_lod.tooltip"))
                        .setDefaultValue(true)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setBinding(v -> store.getData().enable_lod = v, () -> store.getData().lodEnabled())
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createIntegerOption(Identifier.parse("nvidium:lod_start_chunks"))
                        .setName(Component.translatable("nvidium.options.lod_start_chunks.name"))
                        .setTooltip(Component.translatable("nvidium.options.lod_start_chunks.tooltip"))
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED && store.getData().lodEnabled())
                        .setBinding(v -> store.getData().lod_start_chunks = v, () -> store.getData().lodStartChunks())
                        .setRange(16, 256, 8)
                        .setDefaultValue(32)
                        .setValueFormatter(x -> Component.literal(x + " chunks"))
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:enable_temporal_coherence"))
                        .setName(Component.translatable("nvidium.options.enable_temporal_coherence.name"))
                        .setTooltip(Component.translatable("nvidium.options.enable_temporal_coherence.tooltip"))
                        .setDefaultValue(true)
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setBinding(v -> store.getData().enable_temporal_coherence = v, () -> store.getData().enable_temporal_coherence)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:automatic_memory_limit"))
                        .setName(Component.translatable("nvidium.options.automatic_memory_limit.name"))
                        .setTooltip(Component.translatable("nvidium.options.automatic_memory_limit.tooltip"))
                        .setDefaultValue(true)
                        .setImpact(OptionImpact.VARIES)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setBinding(v -> store.getData().automatic_memory = v, () -> store.getData().automatic_memory)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createIntegerOption(Identifier.parse("nvidium:max_gpu_memory"))
                        .setName(Component.translatable("nvidium.options.max_gpu_memory.name"))
                        .setTooltip(Component.translatable("nvidium.options.max_gpu_memory.tooltip"))
                        .setDefaultValue(2048)
                        .setRange(2048, 32768, 512)
                        .setValueFormatter(ControlValueFormatterImpls.translateVariable("nvidium.options.mb"))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED && !c.readBooleanOption(Identifier.parse("nvidium:automatic_memory_limit")), Identifier.parse("nvidium:automatic_memory_limit"))
                        .setBinding(v -> store.getData().max_geometry_memory = v, () -> store.getData().max_geometry_memory)
                        .setFlags(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER ? new OptionFlag[0] : new OptionFlag[]{OptionFlag.REQUIRES_RENDERER_RELOAD})
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:render_fog"))
                        .setName(Component.translatable("nvidium.options.render_fog.name"))
                        .setTooltip(Component.translatable("nvidium.options.render_fog.tooltip"))
                        .setDefaultValue(true)
                        .setBinding(v -> store.getData().render_fog = v, () -> store.getData().render_fog)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:use_sodium_vertex_format"))
                        .setName(Component.translatable("nvidium.options.use_sodium_vertex_format.name"))
                        .setTooltip(Component.translatable("nvidium.options.use_sodium_vertex_format.tooltip"))
                        .setDefaultValue(false)
                        .setBinding(v -> store.getData().use_sodium_vertex_format = v, () -> store.getData().use_sodium_vertex_format)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:cull_degenerate_triangles"))
                        .setName(Component.translatable("nvidium.options.cull_degenerate_triangles.name"))
                        .setTooltip(Component.translatable("nvidium.options.cull_degenerate_triangles.tooltip"))
                        .setDefaultValue(true)
                        .setBinding(v -> store.getData().cull_degenerate_triangles = v, () -> store.getData().cull_degenerate_triangles)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createBooleanOption(Identifier.parse("nvidium:use_nv_fragment_shader_barycentric"))
                        .setName(Component.translatable("nvidium.options.use_nv_fragment_shader_barycentric.name"))
                        .setTooltip(Component.translatable("nvidium.options.use_nv_fragment_shader_barycentric.tooltip"))
                        .setDefaultValue(true)
                        .setBinding(v -> store.getData().use_nv_fragment_shader_barycentric = v, () -> store.getData().use_nv_fragment_shader_barycentric)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createEnumOption(Identifier.parse("nvidium:translucency_sorting"), TranslucencySortingLevel.class)
                        .setName(Component.translatable("nvidium.options.translucency_sorting.name"))
                        .setTooltip(Component.translatable("nvidium.options.translucency_sorting.tooltip"))
                        .setDefaultValue(TranslucencySortingLevel.SODIUM)
                        .setBinding(v -> store.getData().translucency_sorting_level = v, () -> store.getData().translucency_sorting_level)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setElementNameProvider(e -> new Component[]{
                                Component.translatable("nvidium.options.translucency_sorting.none"),
                                Component.translatable("nvidium.options.translucency_sorting.sections"),
                                Component.translatable("nvidium.options.translucency_sorting.quads"),
                                Component.translatable("nvidium.options.translucency_sorting.sodium")
                        }[e.ordinal()])
                        //Technically, only need to reload when going from NONE->SECTIONS
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        nvidiumOptionPage.addOption(
                builder.createEnumOption(Identifier.parse("nvidium:statistics_level"), StatisticsLoggingLevel.class)
                        .setName(Component.translatable("nvidium.options.statistics_level.name"))
                        .setTooltip(Component.translatable("nvidium.options.statistics_level.tooltip"))
                        .setDefaultValue(StatisticsLoggingLevel.NONE)
                        .setBinding(v -> store.getData().statistics_level = v, () -> store.getData().statistics_level)
                        .setEnabledProvider(c -> Nvidium.IS_ENABLED)
                        .setImpact(OptionImpact.LOW)
                        .setElementNameProvider(e -> new Component[]{
                                Component.translatable("nvidium.options.statistics_level.none"),
                                Component.translatable("nvidium.options.statistics_level.frustum"),
                                Component.translatable("nvidium.options.statistics_level.regions"),
                                Component.translatable("nvidium.options.statistics_level.sections"),
                                Component.translatable("nvidium.options.statistics_level.quads"),
                                Component.translatable("nvidium.options.statistics_level.cull")
                        }[e.ordinal()])
                        .setFlags(NvidiumOptionFlags.REQUIRES_SHADER_RELOAD)
                        .setStorageHandler(this.saveConfig)
        );

        builder.registerOwnModOptions()
                .setIcon(Identifier.parse("nvidium:nvidium.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x47F055))
                .addPage(nvidiumOptionPage);
    }
}
