package me.cortex.nvidium;

import org.lwjgl.system.*;

public class DriverFix {
    public static void patch() {
        if (Platform.get() == Platform.LINUX) {
            setLinuxDisableEnv();
        }
    }

    private static void setLinuxDisableEnv() {
        final SharedLibrary sharedLibrary = Library.loadNative("me.cortex.nvidium", "libc.so.6");
        final long pfnSetenv = APIUtil.apiGetFunctionAddress(sharedLibrary, "setenv");
        try (var stack = MemoryStack.stackPush()) {
            JNI.callPPI(MemoryUtil.memAddress0(stack.UTF8("__GL_THREADED_OPTIMIZATIONS")),MemoryUtil.memAddress0(stack.UTF8("0")), 1, pfnSetenv);
        }
    }
}
