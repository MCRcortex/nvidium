package me.cortex.nvidium.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.sodiumCompat.*;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.*;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.mixin.core.GlCommandEncoderAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false, priority = 1500) // Ensure priority over Iris so it doesn't hijack our ChunkVertexFormat
public class MixinRenderSectionManager implements INvidiumWorldRendererGetter {
    @Shadow @Final private RenderRegionManager regions;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow private @NotNull Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;
    @Unique private NvidiumWorldRenderer renderer;
    @Unique private Viewport viewport;

    @Unique
    private static void updateNvidiumIsEnabled() {
        Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE && IrisCheck.checkIrisShouldDisable();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED) {
            if (renderer != null)
                throw new IllegalStateException("Cannot have multiple world renderers");
            renderer = new NvidiumWorldRenderer(Nvidium.config.async_bfs?new AsyncOcclusionTracker(renderDistance, sectionByPosition, level, taskLists):null);
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(renderer);
        }
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V", remap = true), index = 1)
    private ChunkVertexType modifyVertexType(ChunkVertexType vertexType) {
        updateNvidiumIsEnabled();
        if (Nvidium.IS_ENABLED && !Nvidium.config.use_sodium_vertex_format) {
            return NvidiumCompactChunkVertex.INSTANCE;
        }
        return vertexType;
    }


    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            if (renderer == null)
                throw new IllegalStateException("Pipeline already destroyed");
            ((INvidiumWorldRendererSetter)regions).setWorldRenderer(null);
            renderer.delete();
            renderer = null;
        }
    }

    @Redirect(method = "onSectionRemoved", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;delete()V"))
    private void deleteSection(RenderSection section) {
        if (Nvidium.IS_ENABLED) {
            if (Nvidium.config.region_keep_distance == 32 ||
                    Nvidium.config.region_keep_distance <= Minecraft.getInstance().options.getEffectiveRenderDistance()) {
                renderer.deleteSection(section);
            }
        }
        section.delete();
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, CallbackInfo ci) {
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.CUTOUT) // Early exit, cutout will be rendered with SOLID
                return;

            RenderTarget target = pass.getTarget();
            GlStateManager._viewport(0, 0, target.getColorTexture().getWidth(0), target.getColorTexture().getHeight(0));
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, ((GlTexture) target.getColorTexture()).getFbo(((GlDevice) RenderSystem.getDevice()).directStateAccess(), target.getDepthTexture()));
            ((GlCommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).sodium$applyPipelineState(pass.getPipeline());
            ((GlCommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).sodium$setLastProgram(null);

            if (pass == DefaultTerrainRenderPasses.SOLID) {
                renderer.renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                renderer.renderTranslucent(pass, terrainSampler);
            }
        }
    }

    @Inject(method = "getDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(boolean showAdvanced, CallbackInfoReturnable<Collection<String>> cir) {
        if (Nvidium.IS_ENABLED) {
            var debugStrings = new ArrayList<String>();
            renderer.addDebugInfo(debugStrings);
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }

    @Override
    public NvidiumWorldRenderer getRenderer() {
        return renderer;
    }

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
    private void redirectTerrainRenderList(Camera camera, Viewport viewport, FogParameters fogParameters, int frame, boolean spectator, CallbackInfoReturnable<Boolean> cir) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Redirect(method = "processChunkBuildResults(Ljava/util/ArrayList;)Z", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SectionCollector;visitWithFlags(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V"))
    public void processChunkBuildResultsVisit(SectionCollector instance, RenderSection section, int flags) {
        if  (Nvidium.IS_ENABLED && !Nvidium.config.async_bfs) {
            instance.visitWithFlags(section, flags);
        }
    }

    @Redirect(method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;clearPendingUpdate()V"))
    private void injectEnqueueFalse(RenderSection instance) {
        instance.clearPendingUpdate();
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            //We need to reset the fact that its been submitted to the rebuild queue from the build queue
            ((IRenderSectionExtension) instance).isSubmittedRebuild(false);
        }
    }

    @Unique
    private boolean isSectionVisibleBfs(RenderSection section) {
        //The reason why this is done is that since the bfs search is async it could be updating the frame counter with the next frame
        // while some sections that arnt updated/ticked yet still have the old frame id
        int delta = Math.abs(section.getLastVisibleFrame() - renderer.getAsyncFrameId());
        return delta <= 1;
    }

    @Inject(method = "isSectionVisible", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;getLastVisibleFrame()I", shift = At.Shift.BEFORE), cancellable = true)
    private void redirectIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir, @Local RenderSection render) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            cir.setReturnValue(isSectionVisibleBfs(render));
        }
    }

    @Inject(method = "tickVisibleRenders", at = @At("HEAD"), cancellable = true)
    private void redirectAnimatedSpriteUpdates(CallbackInfo ci) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs && SodiumClientMod.options().performance.animateOnlyVisibleTextures) {
            ci.cancel();
            var sprites = renderer.getAnimatedSpriteSet();
            if (sprites == null) {
                return;
            }
            for (var sprite : sprites) {
                SpriteUtil.INSTANCE.markSpriteActive(sprite);
            }
        }
    }

    //* Probably not needed, unsure for now, should probably delete it later

    @Inject(method = "scheduleRebuild", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z", shift = At.Shift.AFTER))
    private void instantReschedule(int x, int y, int z, boolean playerChanged, CallbackInfo ci, @Local RenderSection section, @Local(ordinal = 3) int pendingUpdate) {
        // this might result in the section being enqueued multiple times, if this gets executed,
        // and the async search sees it at the exactly wrong moment
        // This is a problem when sodium translucency sorting is enabled since translucentData.getGeometryPlanes()
        // can be null on the second ChunkBuildOutput resulting in a NPE
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            var queueType = ChunkUpdateTypes.getQueueType(pendingUpdate,
                    SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType(),
                    SortBehavior.DYNAMIC_DEFER_NEARBY_ZERO_FRAMES.getDeferMode().getImportantRebuildQueueType()
            );
            var queue = taskLists.get(queueType);
            if (isSectionVisibleBfs(section) && !queue.contains(section)) {
                ((IRenderSectionExtension)section).isSubmittedRebuild(true);
                taskLists.get(queueType).add(section);
            }
        }
    }

    @Inject(method = "scheduleSort(JZ)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z"))
    public void promoteScheduleSort(long sectionPos, boolean isDirectTrigger, CallbackInfo ci, @Local RenderSection section, @Local int pendingUpdate) {
        if (Nvidium.IS_ENABLED && section.getPendingUpdate() != 0 && pendingUpdate != section.getPendingUpdate()) {
            // The sorter promoted our task, we need to change the taskList
            var oldQueue = ChunkUpdateTypes.getQueueType(section.getPendingUpdate(),
                    SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType(),
                    SortBehavior.DYNAMIC_DEFER_NEARBY_ZERO_FRAMES.getDeferMode().getImportantRebuildQueueType()
            );
            taskLists.get(oldQueue).remove(section);
            var newQueue = ChunkUpdateTypes.getQueueType(pendingUpdate,
                    SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType(),
                    SortBehavior.DYNAMIC_DEFER_NEARBY_ZERO_FRAMES.getDeferMode().getImportantRebuildQueueType()
            );
            taskLists.get(newQueue).add(section);
        }
    }
    //*/

    @Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
    private void injectVisibilityCount(CallbackInfoReturnable<Integer> cir) {
        if (Nvidium.IS_ENABLED && Nvidium.config.async_bfs) {
            cir.setReturnValue(this.renderer.getAsyncBfsVisibilityCount());
        }
    }
}
