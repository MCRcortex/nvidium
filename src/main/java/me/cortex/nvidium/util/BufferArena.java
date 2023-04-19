package me.cortex.nvidium.util;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.Buffer;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer;

public class BufferArena {
    private static long FALLBACK_SIZE;
    SegmentedManager segments = new SegmentedManager();
    private final RenderDevice device;
    public final IDeviceMappedBuffer buffer;
    private long totalQuads;



    public BufferArena(RenderDevice device, int vertexFormatSize) {
        this.device = device;
        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER) {
            buffer = device.createSparseBuffer(80000000000L);//Create a 80gb buffer
        } else {
            FALLBACK_SIZE = Nvidium.config.fallback_allocation_size * 1024L * 1024L;
            buffer = device.createDeviceOnlyMappedBuffer(FALLBACK_SIZE);//create 2gb allocate
        }
    }

    public int allocQuads(int quadCount) {
        totalQuads += quadCount;
        int addr = (int) segments.alloc(quadCount);
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            psab.ensureAllocated(Integer.toUnsignedLong(addr) * 4L * 20, quadCount * 4L * 20);
        }
        return addr;
    }

    public void free(int addr) {
        int count = segments.free(addr);
        totalQuads -= count;
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            psab.deallocate(Integer.toUnsignedLong(addr) * 4L * 20, count * 4L * 20);
        }
    }

    public long upload(UploadingBufferStream stream, int addr) {
        return stream.getUpload(buffer, Integer.toUnsignedLong(addr)*4L*20, (int) segments.getSize(addr)*4*20);
    }

    public void delete() {
        buffer.delete();
    }

    public int getAllocatedMB() {
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            return (int) ((psab.getPagesCommitted() * PersistentSparseAddressableBuffer.PAGE_SIZE) / (1024 * 1024));
        } else {
            return (int) (FALLBACK_SIZE/(1024*1024));
        }
    }

    public float getFragmentation() {
        long expected = totalQuads * 20 * 4;
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            long have = (psab.getPagesCommitted() * PersistentSparseAddressableBuffer.PAGE_SIZE);
            return (float) ((double) expected / have);
        } else {
            return (float)((double)expected/(FALLBACK_SIZE));
        }
    }
}
