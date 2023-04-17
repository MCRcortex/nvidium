package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

public class NvidiumConfigStore implements OptionStorage<NvidiumConfig> {
    private final NvidiumConfig config;

    public NvidiumConfigStore() {
        config = Nvidium.config;
    }

    @Override
    public NvidiumConfig getData() {
        return config;
    }

    @Override
    public void save() {
        config.save();
    }
}
