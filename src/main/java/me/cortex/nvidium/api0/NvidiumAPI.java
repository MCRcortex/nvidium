package me.cortex.nvidium.api0;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;

public class NvidiumAPI {
    private final String modName;
    public NvidiumAPI(String modName) {
        this.modName = modName;
    }

    /***
     * Forces a render section to not render, guarantees the section will stay hidden until it is marked as visible
     * @param x sectionX pos
     * @param y sectionY pos
     * @param z sectionZ pos
     */
    public void hideSection(int x, int y, int z) {
        if (Nvidium.IS_ENABLED) {
            var renderer = ((INvidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            renderer.getSectionManager().setHideBit(x, y, z, true);
        }
    }

    /***
     * Unhides a render section if it was previously hidden
     * @param x sectionX pos
     * @param y sectionY pos
     * @param z sectionZ pos
     */
    public void showSection(int x, int y, int z) {
        if (Nvidium.IS_ENABLED) {
            var renderer = ((INvidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            renderer.getSectionManager().setHideBit(x, y, z, false);
        }
    }


}
