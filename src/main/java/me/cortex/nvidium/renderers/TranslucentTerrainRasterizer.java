package me.cortex.nvidium.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.mixin.minecraft.FrontendRenderPassAccessor;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static me.cortex.nvidium.gl.shader.ShaderType.*;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

public class TranslucentTerrainRasterizer extends Phase {
    private final Shader shader = Shader.make()
            .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
            .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
            .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                    ShaderDefines.builder()
                            .define("TRANSLUCENT_PASS")
                            .define("ALPHA_CUTOUT", 0.01f)
            ))
            .compile();

    private final Shader[] oitShaders = new Shader[] {
            Shader.make()
                    .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
                    .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
                    .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                            ShaderDefines.builder()
                                    .define("TRANSLUCENT_PASS")
                                    .define("ALPHA_CUTOUT", 0.01f)
                                    .define("OIT")
                                    .define("OIT_WAVELET_RANK", 2)
                                    .define("OIT_COEFF_COUNT", LevelRenderer.OIT_COEFFICIENT_COUNT)
                                    .define("OIT_COEFF_ATTACHMENT_COUNT", LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT)
                                    .define("OIT_ALPHA_ONLY")
                                    .define("OIT_DEPTH_BOUNDS")
                    ))
                    .compile(),
            Shader.make()
                    .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
                    .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
                    .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                            ShaderDefines.builder()
                                    .define("TRANSLUCENT_PASS")
                                    .define("ALPHA_CUTOUT", 0.01f)
                                    .define("OIT")
                                    .define("OIT_WAVELET_RANK", 2)
                                    .define("OIT_COEFF_COUNT", LevelRenderer.OIT_COEFFICIENT_COUNT)
                                    .define("OIT_COEFF_ATTACHMENT_COUNT", LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT)
                                    .define("OIT_ALPHA_ONLY")
                                    .define("OIT_TRANSMITTANCE")
                    ))
                    .compile(),
            Shader.make()
                    .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
                    .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
                    .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                            ShaderDefines.builder()
                                    .define("TRANSLUCENT_PASS")
                                    .define("ALPHA_CUTOUT", 0.01f)
                                    .define("OIT")
                                    .define("OIT_WAVELET_RANK", 2)
                                    .define("OIT_COEFF_COUNT", LevelRenderer.OIT_COEFFICIENT_COUNT)
                                    .define("OIT_COEFF_ATTACHMENT_COUNT", LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT)
                                    .define("OIT_ACCUMULATE")
                    ))
                    .compile()
    };

    public TranslucentTerrainRasterizer() {
    }

    private static void setTexture(GpuTextureView texView, int bindingPoint, GpuSampler sampler) {
        GlTexture tex = (GlTexture) texView.texture();
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + bindingPoint);
        GlStateManager._bindTexture(tex.glId());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_BASE_LEVEL, texView.baseMipLevel());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAX_LEVEL, texView.baseMipLevel() + texView.mipLevels() - 1);
        GL33C.glBindSampler(bindingPoint, ((GlSampler) sampler).getId());
    }

    private static void linkRenderpearlSampler(FrontendRenderPass renderPass, int programId, String name, int bindingPoint) {
        TextureViewAndSampler texViewAndSampler = (TextureViewAndSampler)((FrontendRenderPassAccessor)renderPass).nvidium$getUniforms().get(name);
        setTexture(texViewAndSampler.view(), bindingPoint, texViewAndSampler.sampler());

        int location = GL20C.glGetUniformLocation(programId, name);
        GL41C.glProgramUniform1i(programId, location, bindingPoint);
    }

    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void raster(TerrainRenderPass pass, FrontendRenderPass renderPass, int regionCount, long commandAddr, GpuSampler terrainSampler, OitStage stage) {
        GpuTextureView blockTexture = pass.getAtlas();
        GpuTextureView lightTexture = Minecraft.getInstance().gameRenderer.lightmap();

        setTexture(blockTexture, 0, terrainSampler);

        if (stage == null) { // Traditional translucency
            shader.bind();
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
            setTexture(lightTexture, 1, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        } else { // Oit stuffs, we need to fetch all the uniforms from traditional FrontendRenderPass and bind them, because renderpearl only bind at draw time
            Shader oitShader = oitShaders[stage.ordinal()];
            oitShader.bind();
            GlStateManager._depthMask(false);

            switch (stage) {
                case OitStage.DEPTH_BOUNDS:
                    GlStateManager._enableBlend(0);
                    GlStateManager._blendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE);
                    GlStateManager._blendEquationSeparate(GL14C.GL_MAX, GL14C.GL_MAX);
                    break;
                case OitStage.TRANSMITTANCE:
                    GlStateManager._enableBlend(0);
                    GlStateManager._enableBlend(1);
                    GlStateManager._blendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE);
                    GlStateManager._blendEquationSeparate(GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_ADD);

                    linkRenderpearlSampler(renderPass, oitShader.getId(), "DepthBoundsSampler", 2);
                    break;

                case OitStage.ACCUMULATE:
                    GlStateManager._enableBlend(0);
                    GlStateManager._blendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE);
                    GlStateManager._blendEquationSeparate(GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_ADD);

                    setTexture(lightTexture, 1, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

                    linkRenderpearlSampler(renderPass, oitShader.getId(), "DepthBoundsSampler", 2);
                    linkRenderpearlSampler(renderPass, oitShader.getId(), "Coeff0", 3);
                    linkRenderpearlSampler(renderPass, oitShader.getId(), "Coeff1", 4);
                    break;
            }
        }

        //the +8*6 is to offset to the unassigned dispatch
        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddr, regionCount*8L);//Bind the command buffer
        timing.marker();
        glMultiDrawMeshTasksIndirectNV( 0, regionCount, 0);
        timing.marker();
        timing.tick();
        GL45C.glBindSampler(0, 0);
        GL45C.glBindSampler(1, 0);
    }

    public void delete() {
        super.delete();
        shader.delete();
        for (var s : oitShaders) {
            s.delete();
        }
    }
}
