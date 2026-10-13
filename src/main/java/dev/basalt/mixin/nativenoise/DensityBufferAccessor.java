package dev.basalt.mixin.nativenoise;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DensityBuffer.class)
public interface DensityBufferAccessor {
	@Accessor("values")
	float[] basalt$values();
}
