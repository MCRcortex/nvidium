package me.cortex.nvidium.managers;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.sodiumCompat.IRenderSectionExtension;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionFlags;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.lang.Thread.MAX_PRIORITY;

public class AsyncOcclusionTracker {
    private final OcclusionCuller occlusionCuller;
    private final Thread cullThread;

    private volatile boolean running = true;
    private volatile int frame = 0;
    private volatile Viewport viewport = null;

    private final Semaphore framesAhead = new Semaphore(0);

    private final Deque<List<RenderSection>> atomicBatchedRebuildQueue = new ConcurrentLinkedDeque<>();
    private final AtomicReference<List<RenderSection>> blockEntitySectionsRef = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<Sprite[]> visibleAnimatedSpritesRef = new AtomicReference<>();

    private final Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue;

    private final float renderDistance;

    public AsyncOcclusionTracker(int renderDistance, Long2ReferenceMap<RenderSection> sections, World world, Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue) {
        this.occlusionCuller = new OcclusionCuller(sections, world);
        this.cullThread = new Thread(this::run);
        this.cullThread.setName("Cull thread");
        this.cullThread.setPriority(MAX_PRIORITY);
        this.cullThread.start();
        this.renderDistance = renderDistance * 16f;

        this.outputRebuildQueue = outputRebuildQueue;
    }

    private void run() {

        while (running) {
            framesAhead.acquireUninterruptibly();
            if (!running) break;
            //long startTime = System.currentTimeMillis();

            final boolean animateVisibleSpritesOnly = SodiumClientMod.options().performance.animateOnlyVisibleTextures;
            //The reason for batching is so that ordering is strongly defined
            List<RenderSection> chunkUpdates = new ArrayList<>();
            List<RenderSection> blockEntitySections = new ArrayList<>();
            Set<Sprite> animatedSpriteSet = animateVisibleSpritesOnly?new HashSet<>():null;
            final Consumer<RenderSection> visitor = section -> {
                if ((section.getFlags()&RenderSectionFlags.HAS_BLOCK_ENTITIES)!=0 &&
                        section.getPosition().isWithinDistance(viewport.getChunkCoord(),33)) {//32 rd max chunk distance
                    blockEntitySections.add(section);
                }
                if (animateVisibleSpritesOnly && (section.getFlags()&RenderSectionFlags.HAS_ANIMATED_SPRITES) != 0 &&
                        section.getPosition().isWithinDistance(viewport.getChunkCoord(),33)) {//32 rd max chunk distance (i.e. only animate sprites up to 32 chunks away)
                    var animatedSprites = section.getAnimatedSprites();
                    if (animatedSprites != null) {
                        animatedSpriteSet.addAll(List.of(animatedSprites));
                    }
                }
                if (section.getPendingUpdate() != null && section.getBuildCancellationToken() == null) {
                    if (!((IRenderSectionExtension)section).isEnqueued()) {//If it is enqueued, dont enqueue it again
                        //Set that the section is in the rebuild queue
                        ((IRenderSectionExtension)section).setEnqueued(true);
                        chunkUpdates.add(section);
                    }
                }
            };

            frame++;
            float searchDistance = this.getSearchDistance();
            boolean useOcclusionCulling = true;//this.shouldUseOcclusionCulling(camera, spectator);
            try {
                this.occlusionCuller.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);
            } catch (Throwable e) {
                System.err.println("Error doing traversal");
                e.printStackTrace();
            }

            if (!chunkUpdates.isEmpty()) {
                atomicBatchedRebuildQueue.add(chunkUpdates);
            }
            blockEntitySectionsRef.set(blockEntitySections);
            visibleAnimatedSpritesRef.set(animatedSpriteSet==null?null:animatedSpriteSet.toArray(new Sprite[0]));
            //System.err.println(System.currentTimeMillis()-startTime);
        }
    }

    public final void update(Viewport viewport) {
        this.viewport = viewport;

        if (framesAhead.availablePermits() < 200) {//This stops a runaway when the traversal time is greater than frametime
            framesAhead.release();
        }

        while (!atomicBatchedRebuildQueue.isEmpty()) {
            var batch = atomicBatchedRebuildQueue.poll();
            for (var section : batch) {
                if (section.isDisposed())
                    continue;
                var type = section.getPendingUpdate();
                if (type != null && section.getBuildCancellationToken() == null) {
                    var queue = outputRebuildQueue.get(type);
                    if (queue.size() < type.getMaximumQueueSize()) {
                        queue.add(section);
                    } else {
                        //Reset that the section was not enqueued
                        ((IRenderSectionExtension) section).setEnqueued(false);
                    }
                }
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

    private float getEffectiveRenderDistance() {
        float[] color = RenderSystem.getShaderFogColor();
        float distance = RenderSystem.getShaderFogEnd();
        float renderDistance = this.getRenderDistance();
        return !MathHelper.approximatelyEquals(color[3], 1.0F) ? renderDistance : Math.min(renderDistance, distance + 0.5F);
    }

    private float getRenderDistance() {
        return (float)this.renderDistance;
    }

    public int getFrame() {
        return frame;
    }

    public List<RenderSection> getLatestSectionsWithEntities() {
        return blockEntitySectionsRef.get();
    }

    @Nullable
    public Sprite[] getVisibleAnimatedSprites() {
        return visibleAnimatedSpritesRef.get();
    }
}
