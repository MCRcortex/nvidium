package me.cortex.nvidium.util;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;

public class TickableManager {
    private static final List<WeakReference<UploadingBufferStream>> WEAK_UPLOAD_LIST = new LinkedList<>();
    private static final List<WeakReference<DownloadTaskStream>> WEAK_DOWNLOAD_LIST = new LinkedList<>();
    public static void register(UploadingBufferStream stream) {
        WEAK_UPLOAD_LIST.add(new WeakReference<>(stream));
    }
    public static void register(DownloadTaskStream stream) {
        WEAK_DOWNLOAD_LIST.add(new WeakReference<>(stream));
    }

    public static void TickAll() {//Should be called at the very end of the frame
        var iter = WEAK_UPLOAD_LIST.iterator();
        while (iter.hasNext()) {
            var ref = iter.next().get() ;
            if (ref != null) {
                ref.tick();
            } else {
                iter.remove();
            }
        }

        var iter2 = WEAK_DOWNLOAD_LIST.iterator();
        while (iter2.hasNext()) {
            var ref = iter2.next().get() ;
            if (ref != null) {
                ref.tick();
            } else {
                iter2.remove();
            }
        }
    }
}
