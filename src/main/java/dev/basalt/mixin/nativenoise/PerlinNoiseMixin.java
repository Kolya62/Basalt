package dev.basalt.mixin.nativenoise;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.nativelib.NativeNoise;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;

/** Routes terrain Perlin noise to the bundled Rust kernel when it is available and self-tested. */
@Mixin(PerlinNoise.class)
public class PerlinNoiseMixin {
	@WrapMethod(method = "addToVolume")
	private void basalt$native(DensityBuffer buffer, DensityVolume volume, double xzScale, double yScale, float amplitude, Operation<Void> original) {
		if (!NativeNoise.addToVolume((PerlinNoise) (Object) this, buffer, volume, xzScale, yScale, amplitude)) {
			original.call(buffer, volume, xzScale, yScale, amplitude);
		}
	}
}
