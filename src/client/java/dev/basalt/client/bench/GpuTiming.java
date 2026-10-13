package dev.basalt.client.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * GPU timestamps per frame: whole frame and terrain draws. Results are read FRAMES frames later
 * so nothing waits on the GPU. Enabled with the benchmark only.
 */
public final class GpuTiming {
	private static final int FRAMES = 8;
	private static final int SLOTS = 2 + 2 * 4 + 2; // frame start/end + up to 4 terrain groups + culling compute
	private static GpuQueryPool pool;
	private static long frame;
	private static int terrainInFrame;
	private static double periodNs;
	public static boolean recording;
	private static long frames, frameTicks, terrainTicks, cullTicks;

	private GpuTiming() {
	}

	private static int base(long f) {
		return (int) (f % FRAMES) * SLOTS;
	}

	public static void frameStart() {
		if (pool == null) {
			pool = RenderSystem.getDevice().createTimestampQueryPool(FRAMES * SLOTS);
			periodNs = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		}
		frame++;
		if (frame > FRAMES) {
			collect(frame - FRAMES);
		}
		terrainInFrame = 0;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		enc.writeTimestamp(pool, base(frame));
	}

	public static void frameEnd() {
		if (pool != null) {
			RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, base(frame) + 1);
		}
	}

	/** Around the GPU-culling compute work (recorded outside render passes). */
	public static void cull(boolean start) {
		if (pool != null) {
			RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, base(frame) + 10 + (start ? 0 : 1));
		}
	}

	public static void terrain(RenderPass pass, boolean start) {
		if (pool == null || terrainInFrame >= 4) {
			return;
		}
		pass.writeTimestamp(pool, base(frame) + 2 + terrainInFrame * 2 + (start ? 0 : 1));
		if (!start) {
			terrainInFrame++;
		}
	}

	private static void collect(long f) {
		if (!recording) {
			return;
		}
		OptionalLong[] v = pool.getValues(base(f), SLOTS);
		if (v[0].isEmpty() || v[1].isEmpty()) {
			return;
		}
		frames++;
		frameTicks += v[1].getAsLong() - v[0].getAsLong();
		if (v[10].isPresent() && v[11].isPresent() && v[11].getAsLong() >= v[10].getAsLong()) {
			cullTicks += v[11].getAsLong() - v[10].getAsLong();
		}
		for (int g = 0; g < 4; g++) {
			OptionalLong s = v[2 + g * 2], e = v[3 + g * 2];
			if (s.isPresent() && e.isPresent() && e.getAsLong() >= s.getAsLong()) {
				terrainTicks += e.getAsLong() - s.getAsLong();
			}
		}
	}

	public static String report() {
		if (frames == 0) {
			return "gpu_frame_us=-1\ngpu_terrain_us=-1\n";
		}
		return String.format(Locale.ROOT, "gpu_frame_us=%.1f%ngpu_terrain_us=%.1f%ngpu_cull_us=%.1f%n", frameTicks * periodNs / 1e3 / frames,
			terrainTicks * periodNs / 1e3 / frames, cullTicks * periodNs / 1e3 / frames);
	}
}
