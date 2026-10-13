package dev.basalt.nativelib;

import dev.basalt.Basalt;
import dev.basalt.mixin.nativenoise.DensityBufferAccessor;
import dev.basalt.mixin.nativenoise.GradientNoiseAccessor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * PerlinNoise.addToVolume through the Rust kernel. Inputs are passed as heap segments (perms
 * byte[], density float[], a per-thread params long[]) to a critical downcall, so nothing is copied.
 * Before first use a self-test compares Java and Rust bit-for-bit; any mismatch disables the path.
 */
public final class NativeNoise {
	/** -Dbasalt.nativenoise.verify=true: also run Java on every call and compare. */
	private static final boolean VERIFY = Boolean.getBoolean("basalt.nativenoise.verify");
	private static final double HALF_ROUND_OFF = Math.nextDown(1.6777216E7);
	private static final ThreadLocal<long[]> PARAMS = ThreadLocal.withInitial(() -> new long[12]); // 96 bytes, 8-aligned
	private static volatile int state; // 0 = untested, 2 = testing, 1 = ok, -1 = disabled
	/** Set while we deliberately run vanilla Java (self-test, verify) so the mixin doesn't route back here. */
	private static final ThreadLocal<Boolean> JAVA_ONLY = ThreadLocal.withInitial(() -> false);

	private NativeNoise() {
	}

	/** True if the native kernel handled the call; false means the caller must run vanilla. */
	public static boolean addToVolume(PerlinNoise noise, DensityBuffer buffer, DensityVolume volume, double xzScale, double yScale, float amplitude) {
		if (JAVA_ONLY.get()) {
			return false;
		}
		int s = state;
		if (s == 0) {
			s = selfTest();
		}
		if (s != 1) {
			return false;
		}
		float[] values = ((DensityBufferAccessor) buffer).basalt$values();
		if (values.length < volume.size() || volume.size() == 0) {
			return false; // let vanilla handle (and throw on) odd cases; native code must never overrun
		}
		float[] expected = VERIFY ? values.clone() : null;
		call(noise, values, volume, xzScale, yScale, amplitude);
		if (expected != null) {
			DensityBuffer javaBuffer = DensityBuffer.createUnpooled(expected.length);
			for (int i = 0; i < expected.length; i++) {
				javaBuffer.set(i, expected[i]);
			}
			javaAddToVolume(noise, javaBuffer, volume, xzScale, yScale, amplitude);
			for (int i = 0; i < volume.size(); i++) {
				if (Float.floatToRawIntBits(javaBuffer.get(i)) != Float.floatToRawIntBits(values[i])) {
					throw new IllegalStateException("Basalt native noise mismatch at " + i + ": " + javaBuffer.get(i) + " vs " + values[i]);
				}
			}
		}
		return true;
	}

	private static void call(PerlinNoise noise, float[] values, DensityVolume v, double xzScale, double yScale, float amplitude) {
		GradientNoiseAccessor acc = (GradientNoiseAccessor) noise;
		long[] raw = PARAMS.get();
		MemorySegment p = MemorySegment.ofArray(raw);
		p.set(ValueLayout.JAVA_DOUBLE, 0, acc.basalt$offsetX());
		p.set(ValueLayout.JAVA_DOUBLE, 8, acc.basalt$offsetY());
		p.set(ValueLayout.JAVA_DOUBLE, 16, acc.basalt$offsetZ());
		p.set(ValueLayout.JAVA_DOUBLE, 24, xzScale);
		p.set(ValueLayout.JAVA_DOUBLE, 32, yScale);
		p.set(ValueLayout.JAVA_DOUBLE, 40, HALF_ROUND_OFF);
		p.set(ValueLayout.JAVA_FLOAT, 48, amplitude);
		p.set(ValueLayout.JAVA_INT, 52, v.minBlockX());
		p.set(ValueLayout.JAVA_INT, 56, v.minBlockY());
		p.set(ValueLayout.JAVA_INT, 60, v.minBlockZ());
		p.set(ValueLayout.JAVA_INT, 64, v.stepBlockX());
		p.set(ValueLayout.JAVA_INT, 68, v.stepBlockY());
		p.set(ValueLayout.JAVA_INT, 72, v.stepBlockZ());
		p.set(ValueLayout.JAVA_INT, 76, v.sizeX());
		p.set(ValueLayout.JAVA_INT, 80, v.sizeY());
		p.set(ValueLayout.JAVA_INT, 84, v.sizeZ());
		try {
			NativeLib.get().perlinAddToVolume.invokeExact(p, MemorySegment.ofArray(acc.basalt$perms()), MemorySegment.ofArray(values));
		} catch (Throwable t) {
			throw new IllegalStateException("Basalt native noise call failed", t);
		}
	}

	private static void javaAddToVolume(PerlinNoise noise, DensityBuffer buffer, DensityVolume volume, double xz, double ys, float amp) {
		JAVA_ONLY.set(true);
		try {
			noise.addToVolume(buffer, volume, xz, ys, amp);
		} finally {
			JAVA_ONLY.set(false);
		}
	}

	private static synchronized int selfTest() {
		if (state != 0) {
			return state == 2 ? -1 : state;
		}
		NativeLib lib = NativeLib.get();
		if (lib == null) {
			return state = -1;
		}
		state = 2; // other threads use vanilla until the test finishes
		try {
			Random rnd = new Random(20261011);
			long compared = 0;
			for (int t = 0; t < 300; t++) {
				PerlinNoise noise = new PerlinNoise(RandomSource.create(rnd.nextLong()));
				boolean far = t % 4 == 0;
				DensityVolume v = new DensityVolume(1 + rnd.nextInt(16), 1 + rnd.nextInt(48), 1 + rnd.nextInt(16),
					far ? 33_550_000 * (rnd.nextBoolean() ? 1 : -1) + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000,
					rnd.nextInt(400) - 64, far ? -33_550_000 + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000,
					1 + rnd.nextInt(4), 1 + rnd.nextInt(8), 1 + rnd.nextInt(4));
				double xz = new double[] {1.0, 0.25, 684.412 / 1024, 1.0 / 3.0, rnd.nextDouble()}[rnd.nextInt(5)];
				double ys = new double[] {1.0, 0.125, 684.412 / 2048, rnd.nextDouble() * 2}[rnd.nextInt(4)];
				float amp = (float) (rnd.nextDouble() * 2 - 1);
				int n = v.size();
				DensityBuffer java = DensityBuffer.createUnpooled(n);
				float[] nat = new float[n];
				for (int i = 0; i < n; i++) {
					float init = rnd.nextInt(3) == 0 ? 0f : (float) rnd.nextGaussian();
					java.set(i, init);
					nat[i] = init;
				}
				javaAddToVolume(noise, java, v, xz, ys, amp);
				call(noise, nat, v, xz, ys, amp);
				for (int i = 0; i < n; i++, compared++) {
					if (Float.floatToRawIntBits(java.get(i)) != Float.floatToRawIntBits(nat[i])) {
						Basalt.LOGGER.warn("[native] self-test mismatch ({} vs {}), native noise disabled", java.get(i), nat[i]);
						return state = -1;
					}
				}
			}
			Basalt.LOGGER.info("[native] noise self-test passed ({} values bit-identical), native Perlin noise enabled", compared);
			return state = 1;
		} catch (Throwable t) {
			Basalt.LOGGER.warn("[native] self-test failed, native noise disabled", t);
			return state = -1;
		}
	}
}
