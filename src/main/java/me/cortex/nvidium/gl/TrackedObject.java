package me.cortex.nvidium.gl;

import me.cortex.nvidium.Nvidium;

import java.lang.ref.Cleaner;

public abstract class TrackedObject {
    private final Ref ref;
    public TrackedObject() {
        this.ref = register(this);
    }

    protected void free0() {
        if (this.isFreed()) {
            throw new IllegalStateException("Object " + this + " was double freed.");
        }
        this.ref.freedRef[0] = true;
        this.ref.cleanable.clean();
    }

    public abstract void free();

    public void assertNotFreed() {
        if (isFreed()) {
            throw new IllegalStateException("Object " + this + " should not be free, but is");
        }
    }

    public boolean isFreed() {
        return this.ref.freedRef[0];
    }

    public record Ref(Cleaner.Cleanable cleanable, boolean[] freedRef) {}

    private static final Cleaner cleaner = Cleaner.create();
    public static Ref register(Object obj) {
        String clazz = obj.getClass().getName();
        Throwable trace;
        if (Nvidium.IS_DEBUG) {
            trace = new Throwable();
        } else {
            trace = null;
        }
        boolean[] freed = new boolean[1];
        var clean = cleaner.register(obj, ()->{
            if (!freed[0]) {
                System.err.println("Object named: "+ clazz+" was not freed, location at:\n");
                if (trace != null) {
                    trace.printStackTrace();
                } else {
                    System.err.println("Unknown location, turn on debug mode");
                }
                System.err.flush();
            }
        });
        return new Ref(clean, freed);
    }
}
