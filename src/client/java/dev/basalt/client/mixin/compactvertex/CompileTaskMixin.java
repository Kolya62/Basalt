package dev.basalt.client.mixin.compactvertex;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.BasaltConfig;
import dev.basalt.client.compactvertex.CompactTerrain;
import dev.basalt.client.compactvertex.FacingHolder;
import dev.basalt.client.compactvertex.FacingInfo;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunk meshes are still built in vanilla's BLOCK format; repack them right before they are staged
 * for upload. Opaque layers are also regrouped by quad facing for per-direction culling (facecull).
 * <p>
 * The repack runs here, in the compile task, before vanilla's addSectionBuffersToUberBuffer takes
 * the dispatcher-wide copyLock (which the render thread also takes twice per frame). When the
 * staging buffer is full vanilla retries the call in a spin loop; the retry reuses the converted
 * buffer instead of converting the mesh again.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class CompileTaskMixin {
	@Unique private static final boolean BASALT_FACECULL = BasaltConfig.enabled("facecull");
	@Unique private static final ThreadLocal<Converted> BASALT_LAST = ThreadLocal.withInitial(Converted::new);

	/** The last conversion on this thread, keyed by its source, so a spin-loop retry can reuse it. */
	@Unique
	private static final class Converted {
		Object mesh;
		ChunkSectionLayer layer;
		long address;
		int size;
		ByteBuffer out;
		int outSize;
	}

	@WrapOperation(
		method = "doTask",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;addSectionBuffersToUberBuffer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z")
	)
	private boolean basalt$repackVertices(SectionRenderDispatcher.RenderSection section, ChunkSectionLayer layer, CompiledSectionMesh mesh, ByteBuffer vertices,
		ByteBuffer indices, Operation<Boolean> original) {
		if (vertices == null) {
			return original.call(section, layer, mesh, vertices, indices);
		}
		Converted last = BASALT_LAST.get();
		long address = MemoryUtil.memAddress(vertices);
		int size = vertices.remaining();
		if (last.mesh != mesh || last.layer != layer || last.address != address || last.size != size) {
			if (BASALT_FACECULL && !layer.translucent()) {
				FacingInfo[] info = new FacingInfo[1];
				last.out = CompactTerrain.convertSorted(vertices, info);
				((FacingHolder) mesh).basalt$setFacing(layer.ordinal(), info[0]);
			} else {
				last.out = CompactTerrain.convert(vertices);
				((FacingHolder) mesh).basalt$setFacing(layer.ordinal(), null);
			}
			last.outSize = last.out.remaining();
			last.mesh = mesh;
			last.layer = layer;
			last.address = address;
			last.size = size;
		}
		ByteBuffer out = last.out.clear().limit(last.outSize);
		boolean success = original.call(section, layer, mesh, out, indices);
		if (success) {
			last.mesh = null; // the staging buffer has its own copy now; don't keep the mesh reachable
		}
		return success;
	}
}
