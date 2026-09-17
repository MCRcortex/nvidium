package me.cortex.nvidium.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

public class PrimaryTerrainRasterizer extends Phase {
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                    ShaderDefines.builder().define("ALPHA_CUTOUT", 0.5f)
            )).compile();

    public PrimaryTerrainRasterizer() {
    }

    private static void setTexture(GpuTextureView texView, int bindingPoint, GpuSampler sampler) {
        GlTexture tex = (GlTexture) texView.texture();
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + bindingPoint);
        GlStateManager._bindTexture(tex.glId());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_BASE_LEVEL, texView.baseMipLevel());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAX_LEVEL, texView.baseMipLevel() + texView.mipLevels() - 1);
        GL33C.glBindSampler(bindingPoint, ((GlSampler) sampler).getId());
    }

    public void raster(TerrainRenderPass pass, int regionCount, long commandAddr, GpuSampler terrainSampler) {
        shader.bind();

        GpuTextureView blockTexture = pass.getAtlas();
        GpuTextureView lightTexture = Minecraft.getInstance().gameRenderer.lightmap();

        setTexture(blockTexture, 0, terrainSampler);
        setTexture(lightTexture, 1, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr, regionCount*8L);//Bind the command buffer
        timing.marker();
        glMultiDrawMeshTasksIndirectNV( 0, regionCount, 0);
        timing.marker();
        timing.tick();
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
