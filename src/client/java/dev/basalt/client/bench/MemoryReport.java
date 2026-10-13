package dev.basalt.client.bench;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.management.ObjectName;

/** -Dbasalt.bench.memory=true: retained heap after full GCs + class histogram (written next to the report). */
public final class MemoryReport {
	public static final boolean ENABLED = Boolean.getBoolean("basalt.bench.memory");

	private MemoryReport() {
	}

	public static String collect(Path histogramFile) {
		if (!ENABLED) {
			return "";
		}
		try {
			for (int i = 0; i < 3; i++) {
				System.gc();
				Thread.sleep(200);
			}
			long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
			String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
				"gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
			Files.writeString(histogramFile, histogram);
			return String.format(java.util.Locale.ROOT, "heap_used_mb=%.1f%n", used / 1048576.0);
		} catch (Exception e) {
			return "heap_used_mb=-1\n";
		}
	}
}
