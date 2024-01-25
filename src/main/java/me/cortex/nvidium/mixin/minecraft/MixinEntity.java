package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.entities.IEntityRenderDataGetSet;
import org.spongepowered.asm.mixin.Mixin;

import javax.swing.text.html.parser.Entity;

@Mixin(Entity.class)
public class MixinEntity implements IEntityRenderDataGetSet {

}
