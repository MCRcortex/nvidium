package me.cortex.nvidium.renderers;

import static org.lwjgl.opengl.GL43C.glDispatchCompute;

import me.cortex.nvidium.gl.shader.Shader;
import static me.cortex.nvidium.gl.shader.ShaderType.FRAGMENT;
import static me.cortex.nvidium.gl.shader.ShaderType.MESH;
import static me.cortex.nvidium.gl.shader.ShaderType.TASK;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.util.Identifier;

public class SectionRasterizer extends Phase {
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(Identifier.of("nvidium", "occlusion/section_task.glsl")))
            .addSource(MESH, ShaderLoader.parse(Identifier.of("nvidium", "occlusion/section_mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("nvidium", "occlusion/frag.frag"))).compile();

    public SectionRasterizer() {
    }

    public void raster(int regionCount) {
        shader.bind();
        
        // Use compute shader dispatch for AMD compatibility
        // Each workgroup processes one region's sections
        glDispatchCompute(regionCount, 1, 1);
    }

    public void delete() {
        shader.delete();
    }
}
