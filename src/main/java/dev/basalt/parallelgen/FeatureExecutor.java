package dev.basalt.parallelgen;

import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

/**
 * Runs parallel FEATURES steps on their own bounded pool instead of Util.backgroundExecutor().
 * That pool also runs chunk meshing on the client and the light/worldgen dispatchers, and FEATURES
 * steps are long (milliseconds each), so flooding it starved everything else. Tasks are ordered
 * like vanilla's worldgen dispatcher: lowest chunk queue level (closest to a player) first, then
 * submission order.
 * <p>
 * Config: parallelgen.threads = 0 (auto) or a count. Auto: on a dedicated server, as many threads
 * as the background pool (nothing else competes for it there); on a client, half of the pool but at
 * least 2, so meshing keeps workers while features still run in parallel on small CPUs.
 */
public final class FeatureExecutor {
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final ThreadPoolExecutor POOL = create();

	private FeatureExecutor() {
	}

	private static ThreadPoolExecutor create() {
		int configured = 0;
		try {
			configured = Integer.parseInt(BasaltConfig.get("parallelgen.threads", "0").trim());
		} catch (NumberFormatException e) {
			Basalt.LOGGER.warn("[parallelgen] invalid parallelgen.threads, using auto");
		}
		int workers = Util.maxAllowedExecutorThreads();
		boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
		int threads = configured > 0 ? configured : client ? Math.max(Math.min(2, workers), (workers + 1) / 2) : workers;
		AtomicInteger counter = new AtomicInteger(1);
		ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "Basalt-Features-" + counter.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		});
		pool.allowCoreThreadTimeOut(true);
		Basalt.LOGGER.info("[parallelgen] FEATURES pool: {} threads", threads);
		return pool;
	}

	/** Lower priority runs first (vanilla chunk queue level). */
	public static void execute(int priority, Runnable task) {
		POOL.execute(new Task(priority, SEQUENCE.getAndIncrement(), task));
	}

	private record Task(int priority, long sequence, Runnable body) implements Runnable, Comparable<Task> {
		@Override
		public void run() {
			this.body.run();
		}

		@Override
		public int compareTo(Task other) {
			int byPriority = Integer.compare(this.priority, other.priority);
			return byPriority != 0 ? byPriority : Long.compare(this.sequence, other.sequence);
		}
	}
}
