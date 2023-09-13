package me.cortex.nvidium.mixin.sodium;

import com.google.common.collect.ImmutableList;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderPipeline;
import me.cortex.nvidium.config.ConfigGuiBuilder;
import me.cortex.nvidium.config.NvidiumConfigStore;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.sodiumCompat.IRenderPipelineGetter;
import me.cortex.nvidium.sodiumCompat.NvidiumOptionFlags;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
                RenderPipeline pipeline = ((IRenderPipelineGetter)((MixinSodiumWorldRenderer)swr).getRenderSectionManager()).getPipeline();
                if (pipeline != null)
                    pipeline.reloadShaders();
            }
        }
    }
}
