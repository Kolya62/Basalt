package dev.basalt.client.mixin.presentmode;

import com.mojang.renderpearl.api.device.GpuSurface;
import dev.basalt.BasaltConfig;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla VSync always uses FIFO, which queues finished frames and adds up to a couple of
 * refresh intervals of input latency. MAILBOX is also tear-free but always shows the newest
 * frame. Config: vsync_mode = mailbox (default) | fifo.
 */
@Mixin(GpuSurface.PresentMode.class)
public class PresentModeMixin {
	private static final boolean BASALT_MAILBOX = BasaltConfig.get("vsync_mode", "mailbox").equalsIgnoreCase("mailbox");

	@Inject(method = "getSupportedVsyncMode", at = @At("HEAD"), cancellable = true)
	private static void basalt$preferMailbox(Collection<GpuSurface.PresentMode> supportedModes, boolean vsync, CallbackInfoReturnable<GpuSurface.PresentMode> cir) {
		if (vsync && BASALT_MAILBOX && supportedModes.contains(GpuSurface.PresentMode.MAILBOX)) {
			cir.setReturnValue(GpuSurface.PresentMode.MAILBOX);
		}
	}
}
