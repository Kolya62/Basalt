package dev.basalt.client.mixin.drawcache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.drawcache.PackedDraws;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Packed draw lists are copied into the mapped buffer directly instead of element by element. */
@Mixin(DynamicGpuDataStorageMapped.class)
public class DynamicGpuDataStorageMappedMixin {
	@Shadow @Final private int blockSize;

	@WrapOperation(method = "writeDataBatchedMultiple", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
	private Iterator<?> basalt$packedFastPath(List<?> list, Operation<Iterator<?>> original,
		@com.llamalad7.mixinextras.sugar.Local(name = "byteBuffer") ByteBuffer byteBuffer,
		@com.llamalad7.mixinextras.sugar.Local(name = "bufferPositionIndex") com.llamalad7.mixinextras.sugar.ref.LocalIntRef bufferPositionIndex) {
		if (list instanceof PackedDraws packed && this.blockSize == 20) {
			int start = bufferPositionIndex.get();
			for (int i = 0; i < packed.size(); i++) {
				packed.writeTo(byteBuffer, i, (start + i) * 20);
			}
			bufferPositionIndex.set(start + packed.size());
			return java.util.Collections.emptyIterator(); // vanilla's per-element loop then does nothing
		}
		return original.call(list);
	}
}
