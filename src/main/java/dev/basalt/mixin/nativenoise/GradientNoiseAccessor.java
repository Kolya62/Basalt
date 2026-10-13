package dev.basalt.mixin.nativenoise;

import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GradientNoise.class)
public interface GradientNoiseAccessor {
	@Accessor("perms")
	byte[] basalt$perms();

	@Accessor("offsetX")
	double basalt$offsetX();

	@Accessor("offsetY")
	double basalt$offsetY();

	@Accessor("offsetZ")
	double basalt$offsetZ();
}
