package dev.basalt.client.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Linux hardware counter for the calling thread (user-space only), via perf_event_open.
 * Instruction counts do not depend on CPU frequency, unlike time-based metrics on a laptop.
 * Returns -1 everywhere if unavailable (non-Linux, perf_event_paranoid > 2, etc.).
 */
public final class PerfCounter implements AutoCloseable {
	public static final int CYCLES = 0;
	public static final int INSTRUCTIONS = 1;
	private static final long SYS_PERF_EVENT_OPEN = 298; // x86_64
	private static final MethodHandle SYSCALL;
	private static final MethodHandle READ;
	private static final MethodHandle CLOSE;

	static {
		MethodHandle syscall = null, read = null, close = null;
		try {
			if (System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch").equals("amd64")) {
				Linker linker = Linker.nativeLinker();
				var lookup = linker.defaultLookup();
				syscall = linker.downcallHandle(lookup.find("syscall").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
					Linker.Option.firstVariadicArg(1));
				read = linker.downcallHandle(lookup.find("read").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
				close = linker.downcallHandle(lookup.find("close").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			}
		} catch (Throwable ignored) {
			syscall = null;
		}
		SYSCALL = syscall;
		READ = read;
		CLOSE = close;
	}

	private final int fd;
	private final MemorySegment buf = Arena.global().allocate(8);

	public PerfCounter(int config) {
		int fd = -1;
		if (SYSCALL != null) {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment attr = arena.allocate(128);
				attr.set(ValueLayout.JAVA_INT, 0, 0);       // type = PERF_TYPE_HARDWARE
				attr.set(ValueLayout.JAVA_INT, 4, 128);     // size
				attr.set(ValueLayout.JAVA_LONG, 8, config); // config
				attr.set(ValueLayout.JAVA_LONG, 40, 0x60L); // exclude_kernel | exclude_hv; enabled
				fd = (int) (long) SYSCALL.invokeExact(SYS_PERF_EVENT_OPEN, attr, 0, -1, -1, 0L);
			} catch (Throwable t) {
				fd = -1;
			}
		}
		this.fd = fd;
	}

	public long read() {
		if (this.fd < 0) {
			return -1;
		}
		try {
			long n = (long) READ.invokeExact(this.fd, this.buf, 8L);
			return n == 8 ? this.buf.get(ValueLayout.JAVA_LONG, 0) : -1;
		} catch (Throwable t) {
			return -1;
		}
	}

	@Override
	public void close() {
		if (this.fd >= 0) {
			try {
				int ignored = (int) CLOSE.invokeExact(this.fd);
			} catch (Throwable ignored) {
			}
		}
	}
}
