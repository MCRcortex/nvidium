package me.cortex.nvidium.gl.shader;

public interface IShaderProcessor {
    String process(ShaderType type, String source);
}
