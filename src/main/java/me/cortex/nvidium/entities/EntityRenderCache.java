package me.cortex.nvidium.entities;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EntityRenderCache {
    private static final VertexConsumerCapturer capture = new VertexConsumerCapturer();
    private static final MatrixStack stack = new MatrixStack();

    private final Object2LongLinkedOpenHashMap<Entity> renderHashTest = new Object2LongLinkedOpenHashMap<>();
    public void update(Entity entity, float tickDelta) {
        var renderDispatcher = MinecraftClient.getInstance().getEntityRenderDispatcher();
        stack.loadIdentity();
        renderDispatcher.render(entity, 0,0,0, MathHelper.lerp(tickDelta, entity.prevYaw, entity.getYaw()), tickDelta, stack, capture, renderDispatcher.getLight(entity, tickDelta));
        var capture_data = capture.end();

        for (var c : capture_data) {
            //TODO: insert this into the render layer itself via mixin so its fast to access
            var layer = new EncodedRenderLayer(((RenderLayer.MultiPhase)c.getLeft()));

            var shader = ((RenderLayer.MultiPhase)c.getLeft()).phases.program.supplier.get().get();


            long hash = 0;
            var aa = c.getRight().getVertexBuffer();
            var buffer = ByteBuffer.allocateDirect(aa.remaining());
            buffer.put(aa);
            buffer.rewind();
            while (buffer.remaining()>=8) {
                long a = buffer.getLong();
                hash ^= a;
                long z = hash + 0x9e3779b97f4a7c15L;
                z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
                z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
                hash = z ^ (z >>> 31);
                z = hash + 0x9e3779b97f4a7c15L ^ a;
                z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
                z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
                hash = z ^ (z >>> 31);
            }
            buffer.rewind();

            var other = this.renderHashTest.put(entity, hash);
            if (other != hash && hash != 0) {
                System.out.println("Entity data different for entity: " + entity);
            }
        }

    }

    public void remove(Entity entity) {

    }

    public void renderCache() {

    }
}
