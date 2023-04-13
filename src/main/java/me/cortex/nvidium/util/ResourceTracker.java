package me.cortex.nvidium.util;

import java.lang.ref.Cleaner;

public class ResourceTracker {
    private static ResourceTracker TRACKER = new ResourceTracker();
    private final Cleaner cleaner = Cleaner.create();

    public void track(Object object, Runnable destructor) {
        cleaner.register(object, ()->onObjectCleanup(destructor));
    }

    public static void watch(Object object, Runnable cleaner) {
        TRACKER.track(object, cleaner);
    }

    private void onObjectCleanup(Runnable destructor) {
        //TODO: Track cleanup
        destructor.run();
    }
}
