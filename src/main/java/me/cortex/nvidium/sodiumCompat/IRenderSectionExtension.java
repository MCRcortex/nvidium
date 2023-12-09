package me.cortex.nvidium.sodiumCompat;

public interface IRenderSectionExtension {
    boolean isSubmittedRebuild();
    void isSubmittedRebuild(boolean state);

    boolean isSeen();
    void isSeen(boolean state);
}
