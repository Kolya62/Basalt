package dev.basalt.client.bench;

import java.util.Locale;

/**
 * Instruction counts for specific hot methods on the render thread. Unlike whole-frame metrics
 * these exclude GPU-driver waiting (which spins in user space and dominates frame-level noise).
 */
public final class Zones {
	public static final String[] NAMES = {"chunk_prepare", "server_tick"};
	public static final int CHUNK_PREPARE = 0;
	public static final int SERVER_TICK = 1;

	/** perf counters count the thread that opened them, so each thread gets its own. */
	private static final ThreadLocal<PerfCounter> COUNTER = ThreadLocal.withInitial(() -> new PerfCounter(PerfCounter.INSTRUCTIONS));
	private static final long[] TOTAL = new long[NAMES.length];
	private static final long[] COUNT = new long[NAMES.length];
	private static final long[] START = new long[NAMES.length];
	public static volatile boolean recording;

	private Zones() {
	}

	public static void begin(int zone) {
		if (recording) {
			START[zone] = COUNTER.get().read();
		}
	}

	public static void end(int zone) {
		if (recording && START[zone] > 0) {
			TOTAL[zone] += COUNTER.get().read() - START[zone];
			COUNT[zone]++;
			START[zone] = 0;
		}
	}

	public static String report(int frames) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < NAMES.length; i++) {
			// per frame for render zones, per call (e.g. per server tick) otherwise
			double per = i == CHUNK_PREPARE ? frames : Math.max(1, COUNT[i]);
			sb.append(String.format(Locale.ROOT, "zone_%s_kinstr=%.1f%n", NAMES[i], TOTAL[i] / 1e3 / per));
		}
		return sb.toString();
	}
}
