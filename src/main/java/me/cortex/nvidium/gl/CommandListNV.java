package me.cortex.nvidium.gl;

import static org.lwjgl.opengl.NVCommandList.glCreateCommandListsNV;
import static org.lwjgl.opengl.NVCommandList.glDeleteCommandListsNV;

public class CommandListNV {
    private final int list;
    public CommandListNV() {
        list = glCreateCommandListsNV();
    }
    public void delete() {
        glDeleteCommandListsNV(list);
    }
}
