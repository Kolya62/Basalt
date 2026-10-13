package dev.basalt.client.mixin.drawcache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.basalt.client.drawcache.DrawCache;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Any change to where a mesh lives on the GPU invalidates its cached draw description. */
@Mixin(UberGpuBuffer.class)
public class UberGpuBufferMixin {
	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object basalt$invalidateOnPut(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
		DrawCache.invalidate(key);
		return original.call(map, key, value);
	}

	@WrapOperation(method = "freeAllocation", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object basalt$invalidateOnRemove(Map<Object, Object> map, Object key, Operation<Object> original) {
		DrawCache.invalidate(key);
		return original.call(map, key);
	}
}
