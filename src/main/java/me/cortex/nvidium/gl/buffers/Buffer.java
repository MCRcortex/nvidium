package me.cortex.nvidium.gl.buffers;

import me.cortex.nvidium.gl.IResource;

public interface Buffer extends IResource {
    int getId();
    long getSize();
}
