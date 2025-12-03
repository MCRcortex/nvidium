/*
 * Nvidium - High performance rendering engine for Minecraft
 * Copyright (C) 2023 cortex
 *
 * Modified by 1Influence (2025) - Ported to NeoForge.
 * Licensed under LGPL-3.0-only
 */

package me.cortex.nvidium.managers;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.sodiumCompat.IRenderSectionExtension;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.MAX_PRIORITY;

public class AsyncOcclusionTracker {
    private final OcclusionCuller occlusionCuller;
    private final Thread cullThread;
    private final net.minecraft.world.level.Level world;

    private volatile boolean running = true;
    private volatile int frame = 0;
    private volatile Viewport viewport = null;

    private final Semaphore framesAhead = new Semaphore(0);

    private final AtomicReference<List<RenderSection>> atomicBfsResult = new AtomicReference<>();
    private final AtomicReference<List<RenderSection>> blockEntitySectionsRef = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<SpriteSet[]> visibleAnimatedSpritesRef = new AtomicReference<>();

    private final Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue;

    private final float renderDistance;
    private volatile long iterationTimeMillis;
    private volatile boolean shouldUseOcclusionCulling = true;

    private volatile int chunkVisibilityCount = 0;

    public AsyncOcclusionTracker(int renderDistance, Long2ReferenceMap<RenderSection> sections, net.minecraft.world.level.Level world, Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue) {
        this.occlusionCuller = new OcclusionCuller(sections, world);
        this.cullThread = new Thread(this::run);
        this.cullThread.setName("Cull thread");
        this.cullThread.setPriority(MAX_PRIORITY);
        this.cullThread.start();
        this.renderDistance = renderDistance * 16f;

        this.outputRebuildQueue = outputRebuildQueue;
        this.world = world;
    }

    private void run() {
        while (running) {
            framesAhead.acquireUninterruptibly();
            if (!running) break;
            long startTime = System.currentTimeMillis();

            final boolean animateVisibleSpritesOnly = SodiumClientMod.options().performance.animateOnlyVisibleTextures;
            //The reason for batching is so that ordering is strongly defined
            List<RenderSection> chunkUpdates = new ArrayList<>();
            List<RenderSection> blockEntitySections = new ArrayList<>();
            Set<TextureAtlasSprite> animatedSpriteSet = animateVisibleSpritesOnly ? new HashSet<>() : null;
            int[] visibleGeometryCounter = new int[1];

            final OcclusionCuller.Visitor visitor = (section) -> {
                if (section.getPendingUpdate() != null) {
                    if ((!((IRenderSectionExtension)section).isSubmittedRebuild()) && !((IRenderSectionExtension)section).isSeen()) {
                        //If it is in submission queue or seen dont enqueue
                        //Set that the section has been seen
                        ((IRenderSectionExtension)section).isSeen(true);
                        chunkUpdates.add(section);
                    }
                }


                if ((section.getFlags() & (1 << RenderSectionFlags.HAS_BLOCK_GEOMETRY)) != 0) {
                    visibleGeometryCounter[0]++;
                }


                SectionPos sectionPos = section.getPosition();

                if ((section.getFlags() & (1 << RenderSectionFlags.HAS_BLOCK_ENTITIES)) != 0 &&
                        isWithinDistance(sectionPos, viewport.getChunkCoord(), 33)) {
                    //32 rd max chunk distance
                    blockEntitySections.add(section);
                }
                if (animateVisibleSpritesOnly && (section.getFlags() & (1 << RenderSectionFlags.HAS_ANIMATED_SPRITES)) != 0 &&
                        isWithinDistance(sectionPos, viewport.getChunkCoord(), 33)) {
                    //32 rd max chunk distance (i.e. only animate sprites up to 32 chunks away)
                    var animatedSprites = section.getAnimatedSprites();
                    if (animatedSprites != null) {
                        animatedSpriteSet.addAll(List.of(animatedSprites));
                    }
                }
            };

            frame++;
            float searchDistance = this.getSearchDistance();
            boolean useOcclusionCulling = this.shouldUseOcclusionCulling;
            try {
                this.occlusionCuller.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);
            } catch (Throwable e) {
                System.err.println("Error doing traversal");
                e.printStackTrace();
            }

            if (!chunkUpdates.isEmpty()) {
                var previous = atomicBfsResult.getAndSet(chunkUpdates);
                if (previous != null) {
                    //We need to cleanup our state from a previous iteration
                    for (var section : previous) {
                        if (section.isDisposed())
                            continue;
                        //Reset that it hasnt been seen
                        ((IRenderSectionExtension) section).isSeen(false);
                    }
                }
            }
            this.chunkVisibilityCount = visibleGeometryCounter[0];
            blockEntitySectionsRef.set(blockEntitySections);
            visibleAnimatedSpritesRef.set(animatedSpriteSet == null ? null : (SpriteSet[]) animatedSpriteSet.toArray(new TextureAtlasSprite[0]));
            iterationTimeMillis = System.currentTimeMillis() - startTime;
        }
    }

    private boolean isWithinDistance(SectionPos sectionPos, SectionPos cameraPos, int maxChunkDistance) {
        int dx = sectionPos.x() - cameraPos.x();
        int dy = sectionPos.y() - cameraPos.y();
        int dz = sectionPos.z() - cameraPos.z();
        return Math.abs(dx) <= maxChunkDistance &&
                Math.abs(dy) <= maxChunkDistance &&
                Math.abs(dz) <= maxChunkDistance;
    }

    public final void update(Viewport viewport, Camera camera, boolean spectator) {
        this.shouldUseOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

        this.viewport = viewport;

        if (framesAhead.availablePermits() < 5) {
            //This stops a runaway when the traversal time is greater than frametime
            framesAhead.release();
        }

        var bfsResult = atomicBfsResult.getAndSet(null);
        if (bfsResult != null) {
            for (var section : bfsResult) {
                if (section.isDisposed())
                    continue;
                var type = section.getPendingUpdate();
                if (type != null) {
                    var queue = outputRebuildQueue.get(type);
                    if (queue.size() < type.getMaximumQueueSize()) {
                        ((IRenderSectionExtension) section).isSubmittedRebuild(true);
                        queue.add(section);
                    }
                }
                //Reset that the section has not been seen (whether its been submitted to the queue or not)
                ((IRenderSectionExtension) section).isSeen(false);
            }
        }
    }

    public void delete() {
        running = false;
        framesAhead.release(1000);
        try {
            cullThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private float getSearchDistance() {
        return renderDistance;
    }

    private float getSearchDistance2() {
        float distance;
        if (SodiumClientMod.options().performance.useFogOcclusion) {
            distance = this.getEffectiveRenderDistance();
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) {
        BlockPos origin = camera.getBlockPosition();
        boolean useOcclusionCulling;


        boolean isOpaqueFullCube = this.world.getBlockState(origin).isSolidRender(this.world, origin);

        if (spectator && isOpaqueFullCube) {
            useOcclusionCulling = false;
        } else {

            useOcclusionCulling = true;

        }

        return useOcclusionCulling;
    }

    private float getEffectiveRenderDistance() {
        float[] color = RenderSystem.getShaderFogColor();
        float distance = RenderSystem.getShaderFogEnd();
        float renderDistance = this.getRenderDistance();
        return !Mth.equal(color[3], 1.0F) ? renderDistance : Math.min(renderDistance, distance + 0.5F);
    }

    private float getRenderDistance() {
        return this.renderDistance;
    }

    public int getFrame() {
        return frame;
    }

    public List<RenderSection> getLatestSectionsWithEntities() {
        return blockEntitySectionsRef.get();
    }

    @Nullable
    public SpriteSet[] getVisibleAnimatedSprites() {
        return visibleAnimatedSpritesRef.get();
    }

    public long getIterationTime() {
        return this.iterationTimeMillis;
    }

    public int[] getBuildQueueSizes() {
        var ret = new int[this.outputRebuildQueue.size()];
        for (var type : ChunkUpdateType.values()) {
            ret[type.ordinal()] = this.outputRebuildQueue.get(type).size();
        }
        return ret;
    }

    public int getLastVisibilityCount() {
        return this.chunkVisibilityCount;
    }
}