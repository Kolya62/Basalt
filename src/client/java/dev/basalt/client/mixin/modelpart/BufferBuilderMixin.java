package dev.basalt.client.mixin.modelpart;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.basalt.client.modelpart.BulkVertexSink;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements BulkVertexSink {
	@Shadow @Final private ByteBufferBuilder buffer;
	@Shadow private long vertexPointer;
	@Shadow private int vertices;
	@Shadow @Final private boolean entityFormat;
	@Shadow @Final private int vertexSize;
	@Shadow @Final private PrimitiveTopology primitiveTopology;

	@Shadow
	protected abstract void ensureBuilding();

	@Shadow
	protected abstract void endLastVertex();

	@Override
	public long basalt$reserveEntityVertices(int count) {
		if (!this.entityFormat || this.primitiveTopology == PrimitiveTopology.LINES) {
			return -1L;
		}
		// Same checks and resulting state as `count` successive beginVertex() calls on the fast path
		// (which never leaves elements unfilled, so the per-vertex endLastVertex checks are no-ops).
		this.ensureBuilding();
		this.endLastVertex();
		if (this.vertices + count > 16777215) {
			throw new IllegalStateException("Trying to write too many vertices (>16777215) into BufferBuilder");
		}
		this.vertices += count;
		long pointer = this.buffer.reserve(this.vertexSize * count);
		this.vertexPointer = pointer + (long) this.vertexSize * (count - 1);
		return pointer;
	}
}
