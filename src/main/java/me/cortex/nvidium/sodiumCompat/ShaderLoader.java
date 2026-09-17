package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.util.GlslPreprocessor;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

public class ShaderLoader {
    public static String parse(Identifier path) {
        return parse(path, ShaderDefines.builder());
    }

    public static String parse(Identifier path, ShaderDefines.Builder builder) {
        if (Nvidium.IS_DEBUG) {
            builder.define("DEBUG");
        }

        for (int i = 1; i <= Nvidium.config.statistics_level.ordinal(); i++) {
            builder.define("STATISTICS_"+StatisticsLoggingLevel.values()[i].name());
        }


        if (Nvidium.config.translucency_sorting_level.ordinal() >= TranslucencySortingLevel.SECTIONS.ordinal()) {
            builder.define("TRANSLUCENCY_SORTING_SECTIONS");
        }
        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.QUADS) {
            builder.define("TRANSLUCENCY_SORTING_QUADS");
        }
        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            builder.define("TRANSLUCENCY_SORTING_SODIUM");
        }

        if (Nvidium.config.render_fog) {
            builder.define("RENDER_FOG");
        }

        if (Nvidium.config.use_sodium_vertex_format) {
            builder.define("USE_SODIUM_VERTEX_FORMAT");
        }
        if (Nvidium.config.cull_degenerate_triangles) {
            builder.define("CULL_DEGENERATE_TRIANGLES");
        }
        if (Nvidium.config.use_nv_fragment_shader_barycentric) {
            builder.define("USE_NV_FRAGMENT_SHADER_BARYCENTRIC");
        }

        builder.define("TEXTURE_MAX_SCALE", String.valueOf(NvidiumCompactChunkVertex.TEXTURE_MAX_VALUE));

        GlslPreprocessor preprocessor = new GlslPreprocessor(builder.build());
        return preprocessor.process(path);
    }
}
