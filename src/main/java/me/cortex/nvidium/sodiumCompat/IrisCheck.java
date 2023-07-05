package me.cortex.nvidium.sodiumCompat;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

public class IrisCheck {
    public static boolean checkIrisShaders() {
        return IrisApi.getInstance().isShaderPackInUse();
    }
    public static boolean checkIrisShouldDisable() {
        return !(FabricLoader.getInstance().isModLoaded("iris") && checkIrisShaders());
    }
}
