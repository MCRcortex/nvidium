package me.cortex.nvidium.renderers;

import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL45C;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

public class TranslucentTerrainRasterizer extends Phase {
    private final int sampler = glGenSamplers();
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(new Identifier("cortex", "terrain/translucent/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(new Identifier("cortex", "terrain/translucent/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(new Identifier("cortex", "terrain/translucent/frag.frag"))).compile();

    public TranslucentTerrainRasterizer() {
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_LOD, 0);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAX_LOD, 4);
    }


    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void raster(int regionCount, long commandAddr) {
        shader.bind();

        int id = MinecraftClient.getInstance().getTextureManager().getTexture(new Identifier("minecraft", "textures/atlas/blocks.png")).getGlId();

        GL45C.glBindTextureUnit(0, id);
        GL45C.glBindSampler(0, sampler);
        //the +8*6 is to offset to the unassigned dispatch
        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr+8*6, regionCount*8L*7);//Bind the command buffer
        glMultiDrawMeshTasksIndirectNV( 0, regionCount, 8*7);//Since we are using the existing command buffer, has stride of 8*7
        GL45C.glBindSampler(0, 0);
    }
}
