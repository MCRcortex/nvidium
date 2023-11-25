package me.cortex.nvidium.util;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class TickableManager {
    private static final Set<UploadingBufferStream> UPLOADERS = new LinkedHashSet<>();
    private static final Set<DownloadTaskStream> DOWNLOADERS = new LinkedHashSet<>();
    public static void register(UploadingBufferStream stream) {
        UPLOADERS.add(stream);
    }
    public static void register(DownloadTaskStream stream) {
        DOWNLOADERS.add(stream);
    }
    public static void remove(UploadingBufferStream stream) {
        UPLOADERS.remove(stream);
    }
    public static void remove(DownloadTaskStream stream) {
        DOWNLOADERS.remove(stream);
    }

    public static void TickAll() {//Should be called at the very end of the frame
        var iter = UPLOADERS.iterator();
        while (iter.hasNext()) {
            iter.next().tick();
        }

        var iter2 = DOWNLOADERS.iterator();
        while (iter2.hasNext()) {
            iter2.next().tick();
        }
    }
}
