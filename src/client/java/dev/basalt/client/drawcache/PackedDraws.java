package dev.basalt.client.drawcache;

import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;

/**
 * A List&lt;IndexedDraw&gt; stored as packed ints (indexCount, instanceCount, firstIndex, baseVertex,
 * baseInstance). Chunk extraction creates thousands of draws per frame; with this list no
 * IndexedDraw objects exist unless someone reads elements, and the command writer copies the ints
 * straight into the GPU buffer. Backing arrays are recycled once a frame's lists are dropped
 * (render thread only).
 */
public class PackedDraws extends AbstractList<IndexedDraw> implements RandomAccess {
	private static final ArrayDeque<int[]> POOL = new ArrayDeque<>();

	int[] data;
	int size;

	public PackedDraws() {
		int[] pooled = POOL.pollLast();
		this.data = pooled != null ? pooled : new int[40];
	}

	private PackedDraws(int[] data, int size) {
		this.data = data;
		this.size = size;
	}

	/** Returns the backing array to the pool; the list must not be used afterwards. */
	public void recycle() {
		if (this.data != null && POOL.size() < 4096) {
			POOL.addLast(this.data);
		}
		this.data = null;
		this.size = 0;
	}

	public void add(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		if ((this.size + 1) * 5 > this.data.length) {
			this.data = Arrays.copyOf(this.data, this.data.length * 2);
		}
		int o = this.size++ * 5;
		this.data[o] = indexCount;
		this.data[o + 1] = instanceCount;
		this.data[o + 2] = firstIndex;
		this.data[o + 3] = baseVertex;
		this.data[o + 4] = baseInstance;
	}

	@Override
	public boolean add(IndexedDraw d) {
		this.add(d.indexCount(), d.instanceCount(), d.firstIndex(), d.baseVertex(), d.baseInstance());
		return true;
	}

	@Override
	public IndexedDraw get(int i) {
		int o = this.physical(i) * 5;
		return new IndexedDraw(this.data[o], this.data[o + 1], this.data[o + 2], this.data[o + 3], this.data[o + 4]);
	}

	@Override
	public int size() {
		return this.size;
	}

	int physical(int i) {
		if (i < 0 || i >= this.size) {
			throw new IndexOutOfBoundsException(i);
		}
		return i;
	}

	/** Translucent draws are written back to front through reversed(); keep that on the packed path. */
	@Override
	public List<IndexedDraw> reversed() {
		return new Reversed(this);
	}

	/** Writes element i (logical order) like IndexedDraw.write: five ints at position pos. */
	public void writeTo(ByteBuffer buffer, int i, int pos) {
		int o = this.physical(i) * 5;
		buffer.putInt(pos, this.data[o]).putInt(pos + 4, this.data[o + 1]).putInt(pos + 8, this.data[o + 2])
			.putInt(pos + 12, this.data[o + 3]).putInt(pos + 16, this.data[o + 4]);
	}

	/**
	 * Writes all elements (logical order) like IndexedDraw.write, starting at byte position pos, in
	 * the buffer's byte order. A forward list is one bulk copy; the reversed view goes element-wise.
	 */
	public void writeAllTo(ByteBuffer buffer, int pos) {
		if (this.size == 0) {
			return;
		}
		buffer.slice(pos, this.size * 20).order(buffer.order()).asIntBuffer().put(this.data, 0, this.size * 5);
	}

	/** Read-only reversed view sharing the base list's array. */
	private static final class Reversed extends PackedDraws {
		private final PackedDraws base;

		Reversed(PackedDraws base) {
			super(base.data, base.size);
			this.base = base;
		}

		@Override
		int physical(int i) {
			if (i < 0 || i >= this.size) {
				throw new IndexOutOfBoundsException(i);
			}
			return this.size - 1 - i;
		}

		@Override
		public List<IndexedDraw> reversed() {
			return this.base;
		}

		@Override
		public void add(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void writeAllTo(ByteBuffer buffer, int pos) {
			for (int i = 0; i < this.size; i++) {
				this.writeTo(buffer, i, pos + i * 20);
			}
		}

		@Override
		public void recycle() {
			// the view does not own the array
		}
	}
}
