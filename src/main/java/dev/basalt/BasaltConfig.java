package dev.basalt;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Feature toggles. Read once at startup (before mixins apply) from config/basalt.properties.
 * Any key can be overridden with a -Dbasalt.&lt;key&gt;=... system property, which the benchmark
 * harness uses to A/B test a single feature.
 */
public final class BasaltConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("basalt.properties");
	private static final Properties DEFAULTS = new Properties();
	private static final Properties VALUES = new Properties();

	static {
		// Every option, so the file is complete on first launch and stale keys get dropped.
		DEFAULTS.put("brain", "true");
		DEFAULTS.put("chunklevel", "true");
		DEFAULTS.put("collisions", "true");
		DEFAULTS.put("compactvertex", "true");
		DEFAULTS.put("drawcache", "true");
		DEFAULTS.put("entityculling", "true");
		DEFAULTS.put("entitysections", "true");
		DEFAULTS.put("entityculling.max_distance", "128");
		DEFAULTS.put("facecull", "true");
		DEFAULTS.put("fastmap", "true");
		DEFAULTS.put("gpuculling", "auto"); // auto = on at render distance >= 24 (net loss at 16, gain at 32)
		DEFAULTS.put("gpuselect", "true");
		DEFAULTS.put("force_vulkan", "true");
		DEFAULTS.put("gpu", "");
		DEFAULTS.put("modelpart", "true");
		DEFAULTS.put("nativenoise", "true");
		DEFAULTS.put("parallelgen", "true");
		DEFAULTS.put("poi", "true");
		DEFAULTS.put("presentmode", "true");
		DEFAULTS.put("randomtick", "true");
		DEFAULTS.put("vsync_mode", "mailbox");
		DEFAULTS.put("statecache", "true");
		DEFAULTS.put("swapchain", "true");
		DEFAULTS.put("textcache", "true");
		DEFAULTS.put("threaddetect", "true");
		DEFAULTS.put("tint", "true");
		load();
	}

	private BasaltConfig() {
	}

	public static boolean enabled(String feature) {
		return Boolean.parseBoolean(get(feature, "true"));
	}

	public static String get(String key, String def) {
		DEFAULTS.putIfAbsent(key, def);
		String sys = System.getProperty("basalt." + key);
		return sys != null ? sys : VALUES.getProperty(key, def);
	}

	/** The value in the config file (ignores -D overrides); used by the settings screen. */
	public static String stored(String key) {
		return VALUES.getProperty(key, DEFAULTS.getProperty(key, ""));
	}

	public static void set(String key, String value) {
		VALUES.setProperty(key, value);
	}

	public static String defaultValue(String key) {
		return DEFAULTS.getProperty(key, "");
	}

	private static void load() {
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				VALUES.load(r);
			} catch (IOException e) {
				Basalt.LOGGER.warn("Failed to read {}", FILE, e);
			}
		}
	}

	/** Writes every known option (user values kept, unknown keys dropped). */
	public static void save() {
		Properties out = new Properties();
		out.putAll(DEFAULTS);
		VALUES.forEach((k, v) -> {
			if (DEFAULTS.containsKey(k)) {
				out.put(k, v);
			}
		});
		try (Writer w = Files.newBufferedWriter(FILE)) {
			out.store(w, "Basalt feature toggles");
		} catch (IOException e) {
			Basalt.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}
}
