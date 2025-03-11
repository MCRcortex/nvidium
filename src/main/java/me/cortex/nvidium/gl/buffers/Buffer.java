package me.cortex.nvidium.gl.buffers;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;

import me.cortex.nvidium.gl.IResource;

public interface Buffer extends IResource {
    int getId();
    long getSize();
    
    // Add bindBase method for AMD compatibility
    default void bindBase(int target, int index) {
        glBindBufferBase(target, index, getId());
    }
}
