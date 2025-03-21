package me.cortex.nvidium.gl.buffers;


import me.cortex.nvidium.gl.GlObject;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCreateBuffers;
import static org.lwjgl.opengl.ARBDirectStateAccess.glNamedBufferStorage;
import static org.lwjgl.opengl.GL15C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.NVShaderBufferLoad.*;

public class DeviceOnlyMappedBuffer extends GlObject implements IDeviceMappedBuffer {
    public final long size;
    public final long addr;
    public DeviceOnlyMappedBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;
        glNamedBufferStorage(id, size, 0);
        long[] holder = new long[1];
        glGetNamedBufferParameterui64vNV(id, GL_BUFFER_GPU_ADDRESS_NV, holder);
        glMakeNamedBufferResidentNV(id, GL_READ_WRITE);
        addr = holder[0];
        if (addr == 0) {
            throw new IllegalStateException();
        }
    }

    @Override
    public void delete() {
        super.free0();
        glMakeNamedBufferNonResidentNV(id);
        glDeleteBuffers(id);
    }

    @Override
    public long getDeviceAddress() {
        return addr;
    }

    @Override
    public void free() {
        this.delete();
    }

    @Override
    public long getSize() {
        return this.size;
    }
}
