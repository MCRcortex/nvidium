package me.cortex.nvidium.sodiumCompat;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.util.Identifier;

public class ShaderLoader {
    public static String parse(Identifier path) {
        return String.join("\n", ShaderParser.parseShader("#import <"+path.getNamespace()+":"+path.getPath()+">"));
    }
}
