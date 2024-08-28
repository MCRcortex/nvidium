package me.cortex.nvidium.mixin.sodium;

import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.config.ConfigGuiBuilder;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.*;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.*;

@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinSodiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 3, shift = At.Shift.AFTER))
    private void addNvidiumOptions(Screen prevScreen, CallbackInfo ci) {
        ConfigGuiBuilder.addNvidiumGui(pages);
    }

    @Inject(method = "applyChanges", at = @At("RETURN"), locals = LocalCapture.CAPTURE_FAILSOFT)
    private void applyShaderReload(CallbackInfo ci, HashSet<OptionStorage<?>> dirtyStorages, EnumSet<OptionFlag> flags, MinecraftClient client) {
        if (client.world != null) {
            SodiumWorldRenderer swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                NvidiumWorldRenderer pipeline = ((INvidiumWorldRendererGetter)((SodiumWorldRendererAccessor)swr).getRenderSectionManager()).getRenderer();
                if (pipeline != null && flags.contains(NvidiumOptionFlags.REQUIRES_SHADER_RELOAD)) {
                    pipeline.reloadShaders();
                }
            }
        }
    }
}
