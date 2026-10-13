package dev.basalt.client.mixin.drawcache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.drawcache.DrawCache;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mesh swaps and repositioning invalidate the reused previous-frame draw groups. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin {
	@WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicReference;getAndSet(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object basalt$meshChanged(AtomicReference<Object> ref, Object value, Operation<Object> original) {
		DrawCache.EPOCH.incrementAndGet();
		return original.call(ref, value);
	}

	@Inject(method = "setSectionNode", at = @At("HEAD"))
	private void basalt$moved(long sectionNode, CallbackInfo ci) {
		DrawCache.EPOCH.incrementAndGet();
	}
}
