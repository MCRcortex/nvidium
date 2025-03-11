package me.cortex.nvidium.renderers;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import org.lwjgl.opengl.GL12C;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;

import me.cortex.nvidium.gl.shader.Shader;
import static me.cortex.nvidium.gl.shader.ShaderType.FRAGMENT;
import static me.cortex.nvidium.gl.shader.ShaderType.MESH;
import static me.cortex.nvidium.gl.shader.ShaderType.TASK;
import me.cortex.nvidium.mixin.minecraft.LightMapAccessor;
import me.cortex.nvidium.renderers.amd.AMDMeshShaderHelper;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

public class TemporalTerrainRasterizer extends Phase {
    private final int blockSampler = glGenSamplers();
    private final int lightSampler = glGenSamplers();
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(Identifier.of("nvidium", "terrain/temporal/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(Identifier.of("nvidium", "terrain/temporal/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.of("nvidium", "terrain/frag.frag"))).compile();

    public TemporalTerrainRasterizer() {
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MIN_LOD, 0);
        GL45C.glSamplerParameteri(blockSampler, GL45C.GL_TEXTURE_MAX_LOD, 4);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL45C.glSamplerParameteri(lightSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    public void raster(int regionCount, long commandAddr) {
        shader.bind();

        int blockId = MinecraftClient.getInstance().getTextureManager().getTexture(Identifier.of("minecraft", "textures/atlas/blocks.png")).getGlId();
        int lightId = ((LightMapAccessor)MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager()).getTexture().getGlId();

        GL45C.glBindTextureUnit(0, blockId);
        GL45C.glBindSampler(0, blockSampler);

        GL45C.glBindTextureUnit(1, lightId);
        GL45C.glBindSampler(1, lightSampler);

        // Use AMD-compatible approach with standard buffer binding
        int indirectBuffer = (int)(commandAddr & 0xFFFFFFFF); // Extract buffer ID from address
        AMDMeshShaderHelper.drawMeshTasksIndirectNV(indirectBuffer, regionCount);

        GL45C.glBindSampler(0, 0);
        GL45C.glBindSampler(1, 0);
    }

    public void delete() {
        GL45.glDeleteSamplers(blockSampler);
        GL45.glDeleteSamplers(lightSampler);
        shader.delete();
    }
}
