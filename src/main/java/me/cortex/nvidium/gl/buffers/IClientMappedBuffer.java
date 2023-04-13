package me.cortex.nvidium.gl.buffers;

public interface IClientMappedBuffer extends Buffer {
    long clientAddress();
}
