package dev.basalt.client.gpu;

import dev.basalt.Basalt;
import dev.basalt.mixin.nativenoise.GradientNoiseAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * -Dbasalt.gpu.selftest=true: runs PerlinNoise.addToVolume on the GPU and in Java over many random
 * noises/volumes (incl. coordinates near the 2^25 wrap) and compares every float bit-for-bit.
 */
public final class PerlinGpuSelfTest {
	private PerlinGpuSelfTest() {
	}

	public static boolean run(int trials) {
		VkCompute vk = VkCompute.create();
		if (vk == null) {
			Basalt.LOGGER.warn("[gpu] selftest: no suitable device");
			return false;
		}
		Random rnd = new Random(42);
		int maxN = 64 * 64 * 64;
		VkCompute.Buffer params = vk.buffer(128);
		VkCompute.Buffer perms = vk.buffer(256 * 4);
		VkCompute.Buffer out = vk.buffer(maxN * 4L);
		long mismatches = 0, compared = 0, cpuNs = 0, gpuNs = 0;
		try (VkCompute.Kernel kernel = vk.new Kernel(vk.compile("perlin_volume.comp", source()), params, perms, out)) {
			for (int t = 0; t < trials; t++) {
				PerlinNoise noise = new PerlinNoise(RandomSource.create(rnd.nextLong()));
				GradientNoiseAccessor acc = (GradientNoiseAccessor) noise;
				int sx = 1 + rnd.nextInt(16), sy = 1 + rnd.nextInt(64), sz = 1 + rnd.nextInt(16);
				int n = sx * sy * sz;
				boolean far = t % 4 == 0; // exercise wrap() near +-2^25 blocks
				int minX = far ? 33_550_000 * (rnd.nextBoolean() ? 1 : -1) + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000;
				int minZ = far ? -33_550_000 + rnd.nextInt(10_000) : rnd.nextInt(200_000) - 100_000;
				int minY = rnd.nextInt(400) - 64;
				int stepX = 1 + rnd.nextInt(4), stepY = 1 + rnd.nextInt(8), stepZ = 1 + rnd.nextInt(4);
				DensityVolume volume = new DensityVolume(sx, sy, sz, minX, minY, minZ, stepX, stepY, stepZ);
				double xzScale = new double[] {1.0, 0.25, 684.412 / 1024, 0.0078125, 1.0 / 3.0, rnd.nextDouble()}[rnd.nextInt(6)];
				double yScale = new double[] {1.0, 0.125, 684.412 / 2048, rnd.nextDouble() * 2}[rnd.nextInt(4)];
				float amplitude = (float) (rnd.nextDouble() * 2 - 1);

				DensityBuffer cpu = DensityBuffer.createUnpooled(n);
				ByteBuffer o = out.mapped().order(ByteOrder.nativeOrder());
				for (int i = 0; i < n; i++) {
					float init = rnd.nextInt(3) == 0 ? 0f : (float) rnd.nextGaussian();
					cpu.set(i, init);
					o.putFloat(i * 4, init);
				}
				long c0 = System.nanoTime();
				noise.addToVolume(cpu, volume, xzScale, yScale, amplitude);
				cpuNs += System.nanoTime() - c0;

				ByteBuffer p = params.mapped().order(ByteOrder.nativeOrder());
				p.putDouble(0, acc.basalt$offsetX()).putDouble(8, acc.basalt$offsetY()).putDouble(16, acc.basalt$offsetZ())
					.putDouble(24, xzScale).putDouble(32, yScale).putDouble(40, Math.nextDown(1.6777216E7))
					.putFloat(48, amplitude)
					.putInt(52, minX).putInt(56, minY).putInt(60, minZ).putInt(64, stepX).putInt(68, stepY).putInt(72, stepZ)
					.putInt(76, sx).putInt(80, sy).putInt(84, sz);
				ByteBuffer pm = perms.mapped().order(ByteOrder.nativeOrder());
				byte[] src = acc.basalt$perms();
				for (int i = 0; i < 256; i++) {
					pm.putInt(i * 4, src[i] & 0xFF);
				}
				long g0 = System.nanoTime();
				kernel.dispatch((n + 63) / 64);
				gpuNs += System.nanoTime() - g0;

				for (int i = 0; i < n; i++) {
					int a = Float.floatToRawIntBits(cpu.get(i)), b = Float.floatToRawIntBits(o.getFloat(i * 4));
					compared++;
					if (a != b) {
						if (mismatches < 10) {
							Basalt.LOGGER.warn("[gpu] mismatch trial {} index {}: cpu {} ({}) gpu {} ({})", t, i, cpu.get(i), Integer.toHexString(a), o.getFloat(i * 4), Integer.toHexString(b));
						}
						mismatches++;
					}
				}
			}
		} finally {
			vk.free(params);
			vk.free(perms);
			vk.free(out);
			vk.close();
		}
		Basalt.LOGGER.info("[gpu] selftest on {}: {} values compared, {} mismatches (cpu {} ms, gpu incl. submit/wait {} ms)",
			vk.deviceName, compared, mismatches, cpuNs / 1_000_000, gpuNs / 1_000_000);
		return mismatches == 0;
	}

	static String source() {
		try (InputStream in = PerlinGpuSelfTest.class.getResourceAsStream("/assets/basalt/shaders/perlin_volume.comp")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
