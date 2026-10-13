package dev.basalt.client.gpuculling;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * A RenderPass that forwards to a real pass which can be swapped mid-way: vanilla draws the whole
 * main scene in one pass, and GPU occlusion culling needs to end it after the opaque phase (compute
 * work can't run inside a render pass) and continue in a new pass on the same attachments.
 * <p>
 * Plain delegation (no reflection proxy): every draw call of the main pass goes through here.
 */
public final class SplittablePass implements RenderPass {
	private RenderPass current;
	private final Supplier<RenderPass> reopen;
	public final GpuTextureView depth;

	private SplittablePass(RenderPass first, Supplier<RenderPass> reopen, GpuTextureView depth) {
		this.current = first;
		this.reopen = reopen;
		this.depth = depth;
	}

	public static RenderPass wrap(RenderPass first, Supplier<RenderPass> reopen, GpuTextureView depth) {
		return new SplittablePass(first, reopen, depth);
	}

	public static @Nullable SplittablePass of(RenderPass pass) {
		return pass instanceof SplittablePass s ? s : null;
	}

	/** Ends the current real pass, runs {@code between} outside any pass, and continues in a fresh one. */
	public RenderPass split(Runnable between) {
		this.current.close();
		between.run();
		this.current = this.reopen.get();
		return this.current;
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		this.current.pushDebugGroup(label);
	}

	@Override
	public void popDebugGroup() {
		this.current.popDebugGroup();
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		this.current.writeTimestamp(pool, index);
	}

	@Override
	public void setPipeline(CompiledRenderPipeline pipeline) {
		this.current.setPipeline(pipeline);
	}

	@Override
	public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		this.current.setUniform(name, textureView, sampler);
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		this.current.setUniform(name, value);
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		this.current.setUniform(name, value);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		this.current.pushConstants(value);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		this.current.enableScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.current.disableScissor();
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		this.current.setVertexBuffer(slot, vertexBuffer);
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		this.current.setIndexBuffer(indexBuffer, indexType);
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		this.current.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		this.current.multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		this.current.multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		this.current.drawIndexedIndirect(commands, drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType,
		Collection<String> dynamicUniforms, T uniformArgument) {
		this.current.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		this.current.draw(vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		this.current.multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		this.current.multiDraw(firstVertices, vertexCounts, drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		this.current.drawIndirect(commands, drawCount);
	}

	@Override
	public void close() {
		this.current.close();
	}
}
