package dev.basalt.client.bench;

import com.sun.management.OperatingSystemMXBean;
import dev.basalt.Basalt;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Benchmark harness, active only with -Dbasalt.bench=&lt;label&gt;.
 * After joining a world it waits for chunks to build, then spins the camera at a fixed rate
 * while recording per-frame times, writes basalt-bench/&lt;label&gt;.txt and quits the game.
 */
public final class Bench {
	private static final String LABEL = System.getProperty("basalt.bench");
	private static final double WARMUP_S = Double.parseDouble(System.getProperty("basalt.bench.warmup", "20"));
	private static final double DURATION_S = Double.parseDouble(System.getProperty("basalt.bench.duration", "30"));
	private static final float YAW = Float.parseFloat(System.getProperty("basalt.bench.yaw", "0"));
	private static final float PITCH = Float.parseFloat(System.getProperty("basalt.bench.pitch", "10"));
	/** -Dbasalt.bench.f3=true: keep the debug overlay open (GUI-heavy benchmark). */
	private static final boolean KEEP_F3 = Boolean.getBoolean("basalt.bench.f3");
	private static final double DEG_PER_S = Double.parseDouble(System.getProperty("basalt.bench.spin", "18"));
	/** -Dbasalt.bench.resize=&lt;ms&gt;: resize the window every N ms while recording (swapchain stress test). */
	private static final long RESIZE_MS = Long.getLong("basalt.bench.resize", 0L);
	private static final int[][] SIZES = {{854, 480}, {1280, 720}, {1001, 563}, {640, 360}, {1600, 900}, {777, 777}};
	private static int resizes;
	private static boolean settingsShown;
	public static final java.util.concurrent.atomic.AtomicLong MESH_NS = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong MESH_COUNT = new java.util.concurrent.atomic.AtomicLong();
	private static boolean inventoryShown;
	/** -Dbasalt.bench.edit=&lt;blocks per frame&gt;: randomly place/break blocks near the player (mesh rebuild stress test). */
	private static final int EDITS = Integer.getInteger("basalt.bench.edit", 0);
	private static final java.util.Random RANDOM = new java.util.Random(42);
	private static final net.minecraft.world.level.block.Block[] EDIT_BLOCKS = {
		net.minecraft.world.level.block.Blocks.AIR, net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.GLASS,
		net.minecraft.world.level.block.Blocks.WATER, net.minecraft.world.level.block.Blocks.GLOWSTONE, net.minecraft.world.level.block.Blocks.ICE, net.minecraft.world.level.block.Blocks.SLIME_BLOCK
	};
	private static long edits;
	private static long visibleSum;
	private static long msptSumNs;
	private static int throttledFrames;
	private static int debugOverlayFrames;
	private static long firstFrameMs = -1, joinMs = -1;
	private static String startPos = "?";
	private static long lastResizeNs;

	private static long worldJoinNs = -1;
	private static long recordStartNs = -1;
	private static long lastFrameNs = -1;
	private static final java.util.List<String> SLOW = new java.util.ArrayList<>();
	/** -Dbasalt.bench.slowms: frames longer than this are logged with wall-clock times (for tools/slowframes.py). */
	private static final long SLOW_NS = Long.getLong("basalt.bench.slowms", 20L) * 1_000_000L;
	private static long[] frames = new long[1 << 16];
	private static int frameCount;
	private static long cpuStartNs;
	private static long threadCpuStartNs;
	private static long gcCountStart, gcTimeStart;
	private static PerfCounter instr, cycles;
	private static long instrStart, cyclesStart;
	private static java.util.Map<String, Long> threadCpuStart = java.util.Map.of();
	private static boolean done;

	private Bench() {
	}

	public static void onFrame(Minecraft mc) {
		if (done) {
			return;
		}
		long now = System.nanoTime();
		if (firstFrameMs < 0 && mc.gui.screen() != null) {
			firstFrameMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime(); // JVM start -> first menu frame
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			worldJoinNs = -1;
			return;
		}
		if (!settingsShown && worldJoinNs > 0 && now - worldJoinNs > 6_000_000_000L && Boolean.getBoolean("basalt.bench.settingsscreen")) {
			settingsShown = true; // UI check: show the settings screen for the screenshot
			mc.gui.setScreen(new dev.basalt.client.screen.BasaltOptionsScreen(null));
		}
		if (worldJoinNs > 0 && now - worldJoinNs > 6_000_000_000L && Boolean.getBoolean("basalt.bench.settingstest")) {
			SettingsSelfTest.onFrame(mc);
		}
		if (worldJoinNs > 0 && now - worldJoinNs > 8_000_000_000L && Boolean.getBoolean("basalt.bench.inventory") && mc.gui.screen() == null && !inventoryShown) {
			inventoryShown = true;
			mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
		}
		if (worldJoinNs < 0) {
			worldJoinNs = now;

			joinMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
			Basalt.LOGGER.info("[bench] world joined, warming up {}s", WARMUP_S);
			Integer rd = Integer.getInteger("basalt.bench.rd");
			if (rd != null) {
				mc.options.renderDistance().set(rd);
			}
			if (mc.getSingleplayerServer() != null) {
				Scene.build(mc.getSingleplayerServer(), player.blockPosition());
			}
		}
		if (recordStartNs < 0) {
			player.setXRot(PITCH);
			player.setYRot(YAW);
			if ((now - worldJoinNs) / 1e9 < WARMUP_S) {
				return;
			}
			recordStartNs = now;
			lastFrameNs = now;
			cpuStartNs = os().getProcessCpuTime();
			threadCpuStartNs = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
			gcCountStart = gcCount();
			instr = new PerfCounter(PerfCounter.INSTRUCTIONS);
			cycles = new PerfCounter(PerfCounter.CYCLES);
			instrStart = instr.read();
			cyclesStart = cycles.read();
			Zones.recording = true;
			dev.basalt.client.gpuculling.GpuCulling.TIMING = GpuTiming::cull;
			GpuTiming.recording = true;
			if (Boolean.getBoolean("basalt.bench.screenshot")) {
				// first recorded frame, fixed camera (yaw 0, pitch 10)
				net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
					try (image) {
						Path shot = mc.gameDirectory.toPath().resolve("basalt-bench").resolve(LABEL + ".png");
						Files.createDirectories(shot.getParent());
						image.writeToFile(shot);
					} catch (IOException e) {
						Basalt.LOGGER.error("[bench] screenshot failed", e);
					}
				});
			}
			startPos = player.blockPosition().toShortString();
			threadCpuStart = threadCpu();
			gcTimeStart = gcTime();
			Basalt.LOGGER.info("[bench] recording {}s (all sections built: {})", DURATION_S, mc.levelRenderer.hasRenderedAllSections());
			return;
		}

		double t = (now - recordStartNs) / 1e9;
		player.setYRot((float) ((YAW + t * DEG_PER_S) % 360.0));
		if (frameCount == frames.length) {
			frames = Arrays.copyOf(frames, frames.length * 2);
		}
		frames[frameCount++] = now - lastFrameNs;
		if (now - lastFrameNs > SLOW_NS) {
			// wall-clock window of the slow frame, for matching against JFR events
			long endMs = System.currentTimeMillis();
			SLOW.add(String.format(Locale.ROOT, "%d %d", endMs - (now - lastFrameNs) / 1_000_000L, endMs));
		}
		if (mc.getSingleplayerServer() != null) {
			msptSumNs += mc.getSingleplayerServer().getAverageTickTimeNanos();
		}
		if (KEEP_F3) {
			mc.debugEntries.setOverlayVisible(true);
		} else if (mc.debugEntries.isOverlayVisible()) {
			// someone pressed F3 in the bench window: hide it again and mark the run as disturbed
			mc.debugEntries.setOverlayVisible(false);
			debugOverlayFrames++;
		}
		if (mc.getFramerateLimitTracker().getThrottleReason() != com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason.NONE) {
			throttledFrames++;
		}
		OcclusionEstimate.tick(mc, now, ((dev.basalt.client.mixin.bench.LevelRendererAccessor) mc.levelRenderer).basalt$visibleSections());
		visibleSum += ((dev.basalt.client.mixin.bench.LevelRendererAccessor) mc.levelRenderer).basalt$visibleSections().size();
		if (mc.getSingleplayerServer() != null) {
			Scene.tick(mc.getSingleplayerServer(), now);
		}
		if (EDITS > 0 && mc.getSingleplayerServer() != null) {
			net.minecraft.core.BlockPos center = player.blockPosition();
			for (int i = 0; i < EDITS; i++) {
				net.minecraft.core.BlockPos pos = center.offset(RANDOM.nextInt(49) - 24, RANDOM.nextInt(17) - 8, RANDOM.nextInt(49) - 24);
				net.minecraft.world.level.block.state.BlockState state = EDIT_BLOCKS[RANDOM.nextInt(EDIT_BLOCKS.length)].defaultBlockState();
				mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(pos, state));
			}
			edits += EDITS;
		}
		if (RESIZE_MS > 0 && (now - lastResizeNs) / 1_000_000 >= RESIZE_MS) {
			lastResizeNs = now;
			int[] size = SIZES[resizes++ % SIZES.length];
			org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), size[0], size[1]);
		}
		lastFrameNs = now;

		if (t >= DURATION_S) {
			done = true;
			Zones.recording = false;
			GpuTiming.recording = false;
			write(mc, now);
			mc.stop();
		}
	}

	private static OperatingSystemMXBean os() {
		return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
	}

	private static void write(Minecraft mc, long now) {
		double wall = (now - recordStartNs) / 1e9;
		double cpu = (os().getProcessCpuTime() - cpuStartNs) / 1e9;
		double renderCpuUs = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - threadCpuStartNs) / 1e3 / frameCount;
		DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
		long[] sorted = Arrays.copyOf(frames, frameCount);
		Arrays.sort(sorted);
		double avgMs = wall * 1000.0 / frameCount;
		String report = String.format(Locale.ROOT,
			"label=%s%nframes=%d%navg_fps=%.1f%navg_ms=%.3f%np50_ms=%.3f%np99_ms=%.3f%np999_ms=%.3f%nmax_ms=%.3f%n"
				+ "low1_fps=%.1f%nlow01_fps=%.1f%ncpu_cores_used=%.2f%nrender_cpu_us=%.1f%ngpu=%s%nbackend=%s%n",
			LABEL, frameCount, frameCount / wall, avgMs,
			pct(sorted, 0.50), pct(sorted, 0.99), pct(sorted, 0.999), sorted[sorted.length - 1] / 1e6,
			lowFps(sorted, 0.01), lowFps(sorted, 0.001), cpu / wall, renderCpuUs,
			info.name(), info.backendName());
		try {
			Files.write(mc.gameDirectory.toPath().resolve("basalt-bench").resolve(LABEL + ".slow.txt"), SLOW);
		} catch (IOException e) {
			Basalt.LOGGER.warn("slow frame log failed", e);
		}
		long meshes = MESH_COUNT.get();
		report += String.format(Locale.ROOT, "mesh_sections=%d%nmesh_us_per_section=%.1f%n", meshes, meshes == 0 ? 0.0 : MESH_NS.get() / 1000.0 / meshes);
		report += String.format(Locale.ROOT, "frames_over_10ms=%d%nframes_over_25ms=%d%ngc_count=%d%ngc_ms=%d%n",
			over(sorted, 10), over(sorted, 25), gcCount() - gcCountStart, gcTime() - gcTimeStart);
		report += String.format(Locale.ROOT, "render_minstr=%.3f%nrender_mcycles=%.3f%n",
			instrStart < 0 ? -1.0 : (instr.read() - instrStart) / 1e6 / frameCount, cyclesStart < 0 ? -1.0 : (cycles.read() - cyclesStart) / 1e6 / frameCount);
		report += Zones.report(frameCount);
		report += "startup_menu_ms=" + firstFrameMs + "\nstartup_join_ms=" + joinMs + "\n";
		report += GpuTiming.report();
		report += MemoryReport.collect(mc.gameDirectory.toPath().resolve("basalt-bench").resolve(LABEL + ".histo"));
		report += OcclusionEstimate.report();
		report += "throttled_frames=" + (throttledFrames + debugOverlayFrames) + "\n"; // >0: minimized/AFK/F3 shown, run is invalid
		if (Scene.teleports() > 0) {
			report += "teleports=" + Scene.teleports() + "\n";
		}
		report += String.format(Locale.ROOT, "mspt=%.3f%n", msptSumNs / 1e6 / frameCount);
		report += "visible_sections=" + visibleSum / frameCount + "\npos=" + startPos.replace(" ", "") + "\n";
		java.util.Map<String, Long> cpuNow = threadCpu();
		StringBuilder threads = new StringBuilder("threads=");
		cpuNow.entrySet().stream()
			.map(e -> java.util.Map.entry(e.getKey(), (e.getValue() - threadCpuStart.getOrDefault(e.getKey(), 0L)) / 1e9 / wall))
			.filter(e -> e.getValue() > 0.02)
			.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
			.forEach(e -> threads.append(String.format(Locale.ROOT, "%s:%.2f, ", e.getKey(), e.getValue())));
		report += threads + "\n";
		if (EDITS > 0) {
			report += "edits=" + edits + "\n";
		}
		if (RESIZE_MS > 0) {
			report += "resizes=" + resizes + "\n";
		}
		if (dev.basalt.client.drawcache.DrawCache.VERIFY) {
			report += "drawcache_verified_frames=" + dev.basalt.client.drawcache.DrawCache.verifiedFrames + "\n";
		}
		Path out = mc.gameDirectory.toPath().resolve("basalt-bench").resolve(LABEL + ".txt");
		try {
			Files.createDirectories(out.getParent());
			Files.writeString(out, report);
		} catch (IOException e) {
			Basalt.LOGGER.error("[bench] failed to write report", e);
		}
		Basalt.LOGGER.info("[bench] result:\n{}", report);
	}

	/** CPU time per thread-name group (digits stripped so worker pools aggregate). */
	private static java.util.Map<String, Long> threadCpu() {
		java.lang.management.ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		java.util.Map<String, Long> out = new java.util.HashMap<>();
		for (java.lang.management.ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info != null) {
				long t = mx.getThreadCpuTime(info.getThreadId());
				if (t > 0) {
					out.merge(info.getThreadName().replaceAll("[0-9]+", "#"), t, Long::sum);
				}
			}
		}
		return out;
	}

	private static long gcCount() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
	}

	private static long gcTime() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
	}

	private static int over(long[] sorted, double ms) {
		int n = 0;
		for (long f : sorted) {
			if (f / 1e6 > ms) {
				n++;
			}
		}
		return n;
	}

	private static double pct(long[] sorted, double p) {
		return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))] / 1e6;
	}

	/** Average FPS over the slowest fraction of frames ("1% low"). */
	private static double lowFps(long[] sorted, double fraction) {
		int n = Math.max(1, (int) (sorted.length * fraction));
		long sum = 0;
		for (int i = sorted.length - n; i < sorted.length; i++) {
			sum += sorted[i];
		}
		return 1e9 / (sum / (double) n);
	}
}
