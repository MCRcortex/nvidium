package me.cortex.nvidium.gl;

import static org.lwjgl.opengl.NVCommandList.glCreateStatesNV;
import static org.lwjgl.opengl.NVCommandList.glStateCaptureNV;

public class NVState {
    private int id;
    public NVState() {
        id = glCreateStatesNV();
    }

    public void capture(int mode) {
        glStateCaptureNV(id, mode);
    }
}
