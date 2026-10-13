package dev.basalt.nativelib;

import dev.basalt.Basalt;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Loads the bundled Rust library (natives/&lt;os&gt;-&lt;arch&gt;/ inside the mod jar) through the FFM API.
 * Everything here degrades to "not available", in which case callers keep using vanilla Java code.
 */
public final class NativeLib {
	public static final int ABI_VERSION = 1;
	private static final @Nullable NativeLib INSTANCE = load();

	public final MethodHandle perlinAddToVolume;
	public final String platform;
	public final int cpuPath;

	private NativeLib(MethodHandle perlin, String platform, int cpuPath) {
		this.perlinAddToVolume = perlin;
		this.platform = platform;
		this.cpuPath = cpuPath;
	}

	public static @Nullable NativeLib get() {
		return INSTANCE;
	}

	static @Nullable String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : null;
		String a = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
		return o == null || a == null ? null : o + "-" + a;
	}

	private static String fileName(String platform) {
		return platform.startsWith("windows") ? "basalt_native.dll" : platform.startsWith("macos") ? "libbasalt_native.dylib" : "libbasalt_native.so";
	}

	private static @Nullable NativeLib load() {
		String platform = platform();
		if (platform == null) {
			Basalt.LOGGER.info("[native] unsupported platform {} / {}, using Java", System.getProperty("os.name"), System.getProperty("os.arch"));
			return null;
		}
		String resource = "/natives/" + platform + "/" + fileName(platform);
		try (InputStream in = NativeLib.class.getResourceAsStream(resource)) {
			if (in == null) {
				Basalt.LOGGER.info("[native] no library bundled for {}, using Java", platform);
				return null;
			}
			byte[] bytes = in.readAllBytes();
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
			// content-addressed: never overwrite a library another running instance may have loaded
			Path file = FabricLoader.getInstance().getGameDir().resolve(".basalt").resolve("natives").resolve(hash).resolve(fileName(platform));
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Path tmp = Files.createTempFile(file.getParent(), "lib", ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			SymbolLookup lib = SymbolLookup.libraryLookup(file, Arena.global());
			Linker linker = Linker.nativeLinker();
			int abi = (int) linker.downcallHandle(lib.find("basalt_native_abi_version").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
			if (abi != ABI_VERSION) {
				Basalt.LOGGER.warn("[native] library ABI {} != expected {}, using Java", abi, ABI_VERSION);
				return null;
			}
			int cpuPath = (int) linker.downcallHandle(lib.find("basalt_native_cpu_path").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
			// critical(true): short call, no upcalls, and heap arrays may be passed directly (no copies)
			MethodHandle perlin = linker.downcallHandle(lib.find("basalt_perlin_add_to_volume").orElseThrow(),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), Linker.Option.critical(true));
			Basalt.LOGGER.info("[native] loaded {} ({}), cpu path {}", platform, hash, cpuPath == 1 ? "AVX2" : "baseline");
			return new NativeLib(perlin, platform, cpuPath);
		} catch (Throwable t) {
			Basalt.LOGGER.warn("[native] failed to load native library, using Java", t);
			return null;
		}
	}

	/** For logs/bug reports. */
	public static String describe() {
		NativeLib lib = INSTANCE;
		return lib == null ? "unavailable" : lib.platform + (lib.cpuPath == 1 ? " avx2" : " baseline");
	}

}
