package dev.basalt.mixin.nativenoise;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.nativelib.NativeNoise;
import dev.basalt.nativelib.PermsSegmentHolder;
import java.lang.foreign.MemorySegment;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Routes terrain Perlin noise to the bundled Rust kernel when it is available and self-tested. */
@Mixin(PerlinNoise.class)
public class PerlinNoiseMixin implements PermsSegmentHolder {
	/** Wraps the final perms array; racy lazy init is benign (same immutable wrapper either way). */
	@Unique private MemorySegment basalt$perms;

	@WrapMethod(method = "addToVolume")
	private void basalt$native(DensityBuffer buffer, DensityVolume volume, double xzScale, double yScale, float amplitude, Operation<Void> original) {
		if (!NativeNoise.addToVolume((PerlinNoise) (Object) this, buffer, volume, xzScale, yScale, amplitude)) {
			original.call(buffer, volume, xzScale, yScale, amplitude);
		}
	}

	@Override
	public MemorySegment basalt$permsSegment() {
		MemorySegment segment = this.basalt$perms;
		if (segment == null) {
			this.basalt$perms = segment = MemorySegment.ofArray(((GradientNoiseAccessor) this).basalt$perms());
		}
		return segment;
	}
}
