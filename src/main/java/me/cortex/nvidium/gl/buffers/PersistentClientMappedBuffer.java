package me.cortex.nvidium.gl.buffers;


import me.cortex.nvidium.gl.GlObject;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.NVShaderBufferLoad.*;

public class PersistentClientMappedBuffer extends GlObject implements IClientMappedBuffer {
    public final long addr;
    public final long size;

    public PersistentClientMappedBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;
        glNamedBufferStorage(id, size, GL_MAP_PERSISTENT_BIT| (GL_CLIENT_STORAGE_BIT|GL_MAP_WRITE_BIT));
        addr = nglMapNamedBufferRange(id, 0, size, GL_MAP_PERSISTENT_BIT|(GL_MAP_UNSYNCHRONIZED_BIT|GL_MAP_FLUSH_EXPLICIT_BIT|GL_MAP_WRITE_BIT));
    }

    @Override
    public long clientAddress() {
        return addr;
    }

    @Override
    public void delete() {
        super.free0();
        glUnmapNamedBuffer(id);
        glDeleteBuffers(id);
    }

    @Override
    public void free() {
        this.delete();
    }

    @Override
    public long getSize() {
        return size;
    }
}
