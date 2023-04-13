package me.cortex.nvidium.util;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer;

public class BufferArena {
    SegmentedManager segments = new SegmentedManager();
    private final RenderDevice device;
    public final PersistentSparseAddressableBuffer buffer;

    public BufferArena(RenderDevice device, int vertexFormatSize) {
        this.device = device;
        buffer = device.createSparseBuffer(8000000000L);//Create a 8gb buffer
    }

    public int allocQuads(int quadCount) {
        int addr = (int) segments.alloc(quadCount);
        buffer.ensureAllocated(addr*4L*20, quadCount*4L*20);
        return addr;
    }

    public void free(int addr) {
        int count = segments.free(addr);
        buffer.deallocate(addr*4L*20, count*4L*20);
    }

    public long upload(UploadingBufferStream stream, int addr) {
        return stream.getUpload(buffer, addr*4L*20, (int) segments.getSize(addr)*4*20);
    }

    public void delete() {
        buffer.delete();
    }
}
