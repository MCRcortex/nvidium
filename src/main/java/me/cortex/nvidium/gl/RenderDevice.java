package me.cortex.nvidium.gl;

import me.cortex.nvidium.gl.buffers.*;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.ARBDirectStateAccess.glFlushMappedNamedBufferRange;
import static org.lwjgl.opengl.GL15C.glIsBuffer;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;

public class RenderDevice {
    public PersistentClientMappedBuffer createClientMappedBuffer(long size) {
        return new PersistentClientMappedBuffer(size);
    }

    public void flush(IClientMappedBuffer buffer, long offset, int size) {
        int id = ((GlObject)buffer).getId();
        glFlushMappedNamedBufferRange(id, offset, size);
    }

    public void barrier(int flags) {
        glMemoryBarrier(flags);
    }

    public void copyBuffer(Buffer src, Buffer dst, long srcOffset, long dstOffset, long size) {
        glCopyNamedBufferSubData(((GlObject)src).getId(), ((GlObject)dst).getId(), srcOffset, dstOffset, size);
    }

    public PersistentSparseAddressableBuffer createSparseBuffer(long totalSize) {
        return new PersistentSparseAddressableBuffer(totalSize);
    }

    public IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size) {
        return new DeviceOnlyMappedBuffer(size);
    }
}
