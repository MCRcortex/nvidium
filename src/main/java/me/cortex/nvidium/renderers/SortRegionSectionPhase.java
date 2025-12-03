package me.cortex.nvidium.renderers;

import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.ResourceLocation;

import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class SortRegionSectionPhase extends Phase {
    private final Shader shader = Shader.make()
            .addSource(COMPUTE, ShaderLoader.parse(ResourceLocation.fromNamespaceAndPath("nvidium", "sorting/region_section_sorter.comp")))
            .compile();

    public SortRegionSectionPhase() {
    }

    public void dispatch(int sortingRegionCount) {
        shader.bind();
        glDispatchCompute(sortingRegionCount, 1, 1);
    }

    public void delete() {
        shader.delete();
    }
}