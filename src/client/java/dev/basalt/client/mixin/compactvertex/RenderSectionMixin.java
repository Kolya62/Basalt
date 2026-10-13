package dev.basalt.client.mixin.compactvertex;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.basalt.BasaltConfig;
import dev.basalt.client.compactvertex.CompactTerrain;
import dev.basalt.client.compactvertex.FacingHolder;
import dev.basalt.client.compactvertex.FacingInfo;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunk meshes are still built in vanilla's BLOCK format; repack them right before they are staged
 * for upload. Opaque layers are also regrouped by quad facing for per-direction culling (facecull).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin {
	@Unique private static final boolean BASALT_FACECULL = BasaltConfig.enabled("facecull");

	@WrapOperation(
		method = "addSectionBuffersToUberBuffer",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/UberGpuBuffer;addAllocation(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/UberGpuBuffer$UploadCallback;Ljava/nio/ByteBuffer;)Z", ordinal = 0)
	)
	private boolean basalt$repackVertices(UberGpuBuffer<?> buffer, Object key, UberGpuBuffer.UploadCallback<?> callback, ByteBuffer vertices, Operation<Boolean> original,
		@Local(argsOnly = true) ChunkSectionLayer layer, @Local(argsOnly = true) CompiledSectionMesh mesh) {
		if (BASALT_FACECULL && !layer.translucent()) {
			FacingInfo[] info = new FacingInfo[1];
			ByteBuffer sorted = CompactTerrain.convertSorted(vertices, info);
			((FacingHolder) mesh).basalt$setFacing(layer.ordinal(), info[0]);
			return original.call(buffer, key, callback, sorted);
		}
		((FacingHolder) mesh).basalt$setFacing(layer.ordinal(), null);
		return original.call(buffer, key, callback, CompactTerrain.convert(vertices));
	}
}
