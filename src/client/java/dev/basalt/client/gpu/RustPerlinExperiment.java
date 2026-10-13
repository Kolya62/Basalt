package dev.basalt.client.gpu;

import dev.basalt.Basalt;
import dev.basalt.mixin.nativenoise.GradientNoiseAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * -Dbasalt.rust.experiment=&lt;path to libbasalt_native.so&gt;: checks the Rust PerlinNoise kernel
 * bit-for-bit against Java, then times Java vs Rust (compute only, and including the copies a
 * real integration would need).
 */
public final class RustPerlinExperiment {
	private RustPerlinExperiment() {
	}

	public static boolean run(String libraryPath) throws Throwable {
		Arena arena = Arena.global();
		SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(libraryPath), arena);
		MethodHandle kernel = Linker.nativeLinker().downcallHandle(lib.find("basalt_perlin_add_to_volume").orElseThrow(),
			FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		MemorySegment params = arena.allocate(96, 8);
		MemorySegment perms = arena.allocate(256);
		MemorySegment out = arena.allocate(64 * 400 * 64 * 4L, 4);

		// 1. correctness
		Random rnd = new Random(7);
		long compared = 0, mismatches = 0;
		for (int t = 0; t < 2000; t++) {
			PerlinNoise noise = new PerlinNoise(RandomSource.create(rnd.nextLong()));
			int sx = 1 + rnd.nextInt(16), sy = 1 + rnd.nextInt(64), sz = 1 + rnd.nextInt(16);
			boolean far = t % 4 == 0;
			int minX = far ? 33_550_000 * (rnd.nextBoolean() ? 1 : -1) + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000;
			int minZ = far ? -33_550_000 + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000;
			DensityVolume v = new DensityVolume(sx, sy, sz, minX, rnd.nextInt(400) - 64, minZ, 1 + rnd.nextInt(4), 1 + rnd.nextInt(8), 1 + rnd.nextInt(4));
			double xz = new double[] {1.0, 0.25, 684.412 / 1024, 0.0078125, 1.0 / 3.0, rnd.nextDouble()}[rnd.nextInt(6)];
			double ys = new double[] {1.0, 0.125, 684.412 / 2048, rnd.nextDouble() * 2}[rnd.nextInt(4)];
			float amp = (float) (rnd.nextDouble() * 2 - 1);
			int n = v.size();
			DensityBuffer cpu = DensityBuffer.createUnpooled(n);
			for (int i = 0; i < n; i++) {
				float init = rnd.nextInt(3) == 0 ? 0f : (float) rnd.nextGaussian();
				cpu.set(i, init);
				out.setAtIndex(ValueLayout.JAVA_FLOAT, i, init);
			}
			noise.addToVolume(cpu, v, xz, ys, amp);
			fill(params, perms, noise, v, xz, ys, amp);
			kernel.invokeExact(params, perms, out);
			for (int i = 0; i < n; i++) {
				compared++;
				if (Float.floatToRawIntBits(cpu.get(i)) != Float.floatToRawIntBits(out.getAtIndex(ValueLayout.JAVA_FLOAT, i))) {
					if (mismatches++ < 5) {
						Basalt.LOGGER.warn("[rust] mismatch trial {} index {}: java {} rust {}", t, i, cpu.get(i), out.getAtIndex(ValueLayout.JAVA_FLOAT, i));
					}
				}
			}
		}
		Basalt.LOGGER.info("[rust] correctness: {} values compared, {} mismatches", compared, mismatches);

		// 2. speed, after JIT warm-up, on realistic volume shapes
		PerlinNoise noise = new PerlinNoise(RandomSource.create(123));
		for (int[] shape : new int[][] {{5, 49, 5}, {16, 96, 16}}) {
			DensityVolume v = new DensityVolume(shape[0], shape[1], shape[2], 1000, -64, 2000, shape[0] == 5 ? 4 : 1, shape[1] == 49 ? 8 : 4, shape[0] == 5 ? 4 : 1);
			int n = v.size();
			DensityBuffer buf = DensityBuffer.createUnpooled(n);
			float[] heap = new float[n];
			fill(params, perms, noise, v, 684.412 / 1024, 684.412 / 2048, 1f);
			int iters = 200_000 / Math.max(1, n / 1000);
			for (int warm = 0; warm < 3; warm++) {
				long t0 = System.nanoTime();
				for (int i = 0; i < iters; i++) {
					noise.addToVolume(buf, v, 684.412 / 1024, 684.412 / 2048, 1f);
				}
				long java = System.nanoTime() - t0;
				t0 = System.nanoTime();
				for (int i = 0; i < iters; i++) {
					kernel.invokeExact(params, perms, out);
				}
				long rust = System.nanoTime() - t0;
				t0 = System.nanoTime();
				for (int i = 0; i < iters; i++) {
					MemorySegment.copy(heap, 0, out, ValueLayout.JAVA_FLOAT, 0, n);
					kernel.invokeExact(params, perms, out);
					MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0, heap, 0, n);
				}
				long rustCopy = System.nanoTime() - t0;
				if (warm == 2) {
					Basalt.LOGGER.info(String.format(Locale.ROOT, "[rust] volume %dx%dx%d (%d pts): java %.2f us, rust %.2f us (%.2fx), rust+copy %.2f us (%.2fx)",
						shape[0], shape[1], shape[2], n, java / 1e3 / iters, rust / 1e3 / iters, (double) java / rust, rustCopy / 1e3 / iters, (double) java / rustCopy));
				}
			}
		}
		return mismatches == 0;
	}

	private static void fill(MemorySegment params, MemorySegment perms, PerlinNoise noise, DensityVolume v, double xz, double ys, float amp) {
		GradientNoiseAccessor acc = (GradientNoiseAccessor) noise;
		params.set(ValueLayout.JAVA_DOUBLE, 0, acc.basalt$offsetX());
		params.set(ValueLayout.JAVA_DOUBLE, 8, acc.basalt$offsetY());
		params.set(ValueLayout.JAVA_DOUBLE, 16, acc.basalt$offsetZ());
		params.set(ValueLayout.JAVA_DOUBLE, 24, xz);
		params.set(ValueLayout.JAVA_DOUBLE, 32, ys);
		params.set(ValueLayout.JAVA_DOUBLE, 40, Math.nextDown(1.6777216E7));
		params.set(ValueLayout.JAVA_FLOAT, 48, amp);
		int[] ints = {v.minBlockX(), v.minBlockY(), v.minBlockZ(), v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), v.sizeX(), v.sizeY(), v.sizeZ()};
		for (int i = 0; i < ints.length; i++) {
			params.set(ValueLayout.JAVA_INT, 52 + i * 4, ints[i]);
		}
		byte[] src = acc.basalt$perms();
		for (int i = 0; i < 256; i++) {
			perms.set(ValueLayout.JAVA_BYTE, i, src[i]);
		}
	}
}
