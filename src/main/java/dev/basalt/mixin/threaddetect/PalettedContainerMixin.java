package dev.basalt.mixin.threaddetect;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every chunk section's PalettedContainer (block states and biomes, client and server) carries a
 * ThreadingDetector: the detector plus a Semaphore and a ReentrantLock with their sync objects,
 * ~130 bytes each and ~200k of them in a normal world. Its only job is to crash with thread dumps
 * when two threads use one container at once. An int owner flag with CAS plus the owning thread
 * does the same job; the second thread fails immediately instead of after waiting.
 * <p>
 * Acquire is a single CAS; release is a release-store (no StoreLoad fence). The owner thread is
 * only used for the crash report, so it is written plainly.
 */
@Mixin(PalettedContainer.class)
public class PalettedContainerMixin {
	@Unique private static final VarHandle BASALT_STATE;

	static {
		try {
			BASALT_STATE = MethodHandles.lookup().findVarHandle(PalettedContainer.class, "basalt$state", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@Unique private volatile int basalt$state;
	@Unique private Thread basalt$owner; // diagnostics only

	/** One shared, never-used instance (acquire/release below no longer touch it); Mixin forbids null here. */
	@Unique private static final ThreadingDetector BASALT_SHARED = new ThreadingDetector("PalettedContainer (unused)");

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "(Ljava/lang/String;)Lnet/minecraft/util/ThreadingDetector;"))
	private ThreadingDetector basalt$sharedDetector(String name) {
		return BASALT_SHARED;
	}

	/**
	 * @author Basalt
	 * @reason Lightweight replacement of ThreadingDetector.checkAndLock.
	 */
	@Overwrite
	public void acquire() {
		if (!BASALT_STATE.compareAndSet((PalettedContainer<?>) (Object) this, 0, 1)) {
			throw ThreadingDetector.makeThreadingException("PalettedContainer", this.basalt$owner);
		}
		this.basalt$owner = Thread.currentThread();
	}

	/**
	 * @author Basalt
	 * @reason Lightweight replacement of ThreadingDetector.checkAndUnlock.
	 */
	@Overwrite
	public void release() {
		this.basalt$owner = null;
		BASALT_STATE.setRelease((PalettedContainer<?>) (Object) this, 0);
	}
}
