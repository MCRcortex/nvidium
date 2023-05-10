package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.util.Identifier;

public class ShaderLoader {
    public static String parse(Identifier path) {
        var builder = ShaderConstants.builder();
        if (Nvidium.IS_DEBUG) {
            builder.add("DEBUG");
        }
        builder.add("MATERIAL_OVERRIDE", String.valueOf(Nvidium.config.mips_enabled?1:0));
        return ShaderParser.parseShader("#import <"+path.getNamespace()+":"+path.getPath()+">", builder.build());
    }
}
