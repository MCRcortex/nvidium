package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

public class ShaderLoader {
    public static String parse(Identifier path) {
        return parse(path, shaderConstants -> {});
    }

    public static String parse(Identifier path, Consumer<ShaderConstants.Builder> constantBuilder) {
        var builder = ShaderConstants.builder();
        if (Nvidium.IS_DEBUG) {
            builder.add("DEBUG");
        }

        for (int i = 1; i <= Nvidium.config.statistics_level.ordinal(); i++) {
            builder.add("STATISTICS_"+StatisticsLoggingLevel.values()[i].name());
        }


        for (int i = 1; i <= Nvidium.config.translucency_sorting_level.ordinal(); i++) {
            builder.add("TRANSLUCENCY_SORTING_"+TranslucencySortingLevel.values()[i].name());
        }

        if (Nvidium.config.render_fog) {
            builder.add("RENDER_FOG");
        }

        builder.add("TEXTURE_MAX_SCALE", String.valueOf(NvidiumCompactChunkVertex.TEXTURE_MAX_VALUE));
        constantBuilder.accept(builder);

        return ShaderParser.parseShader("#import <"+path.getNamespace()+":"+path.getPath()+">", builder.build());
    }
}
