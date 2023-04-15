package me.cortex.nvidium.util;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.gl.buffers.PersistentClientMappedBuffer;
import me.cortex.nvidium.util.SegmentedManager;

import java.util.function.Consumer;

//Download stream from gpu to cpu
public class DownloadTaskStream {

    public interface IDownloadFinishedCallback {void accept(long addr, long size);}

    private final SegmentedManager allocator = new SegmentedManager();
    private final RenderDevice device;
    private PersistentClientMappedBuffer buffer;//TODO: make it self resizing if full

    public DownloadTaskStream(RenderDevice device, int frames, long size) {
        this.device = device;
        allocator.setLimit(size);
        buffer = device.createClientMappedBuffer(size);
        TickableManager.register(this);
    }

    public void download(Buffer source, long offset, long size, IDownloadFinishedCallback callback) {

    }

    void tick() {

    }
}
