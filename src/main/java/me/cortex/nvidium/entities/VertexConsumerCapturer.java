package me.cortex.nvidium.entities;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Pair;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VertexConsumerCapturer extends VertexConsumerProvider.Immediate implements VertexConsumerProvider {
    private final Map<RenderLayer, BufferBuilder> builderMap = new HashMap<>();

    protected VertexConsumerCapturer() {
        super(null, null);
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        return builderMap.compute(layer, (layer1, builder)-> {
            if (builder == null) {
                builder = new BufferBuilder(420);
            }
            if (!builder.isBuilding()) {
                builder.reset();
                builder.begin(layer1.getDrawMode(), layer1.getVertexFormat());
            }
            return builder;
        });
    }

    public List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> end() {
        List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> buffers = new ArrayList<>();
        builderMap.forEach((layer,buffer)->{
            if (buffer.isBuilding()) {
                var builtBuffer = buffer.end();
                if (builtBuffer.getParameters().getBufferSize() == 0) {
                    return;//Dont add empty buffers
                }
                buffers.add(new Pair<>(layer, builtBuffer));
            }
        });
        return buffers;
    }

    @Override
    public void draw() {

    }

    @Override
    public void draw(RenderLayer layer) {

    }

    @Override
    public void drawCurrentLayer() {

    }
}
