package dev.basalt.client.drawcache;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Everything LevelRenderer.extractSectionDrawGroups needs about one section mesh, resolved once
 * instead of every frame. Built from the same vanilla lookups, so the output is identical; it is
 * dropped whenever the mesh's GPU allocations change (see UberGpuBufferMixin) or the mesh closes.
 */
public final class DrawCache {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

	/** -Dbasalt.drawcache.verify=true compares every frame against vanilla. */
	public static final boolean VERIFY = Boolean.getBoolean("basalt.drawcache.verify");
	public static long verifiedFrames;
	private static final boolean VERIFY_RUNS = Boolean.getBoolean("basalt.facecull.verifyruns");
	public static long RUNS_VERIFIED;

	public final SectionRenderDispatcher owner;
	/** False if some layer has geometry that is not uploaded yet; such a cache is used for one frame only. */
	public final boolean complete;
	public final int count;
	public final ChunkSectionLayer[] layers;
	public final int[] indexCount;
	public final int[] firstIndex;
	public final int[] baseVertex;
	public final int[] groupHash;
	public final boolean[] customIndices;
	public final GpuBuffer[] vertexBuffers;
	public final @Nullable GpuBuffer[] indexBuffers;
	public final @Nullable IndexType[] indexTypes;
	/** Per-direction quad groups of the entry (opaque layers with facecull), else null. */
	public final dev.basalt.client.compactvertex.@Nullable FacingInfo[] facings;

	/**
	 * Face-culled draw runs per entry, keyed by which of the 6 axis-aligned face groups are hidden
	 * (64 cases): [indexCount, firstIndex, ...]. The runs depend only on that pattern, so this is
	 * exact for every camera position. Built lazily.
	 */
	private int[][] runCache;

	private net.minecraft.client.renderer.DynamicGpuData.@Nullable ChunkSectionInfo info;

	/** Equal to new ChunkSectionInfo(x, y, z, visibility); the fully faded-in record is reused across frames. */
	public net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo sectionInfo(int x, int y, int z, float visibility) {
		var cached = this.info;
		if (cached != null && cached.x() == x && cached.y() == y && cached.z() == z && cached.visibility() == visibility) {
			return cached;
		}
		var created = new net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo(x, y, z, visibility);
		if (visibility == 1.0F) {
			this.info = created;
		}
		return created;
	}

	/** Draw runs for entry i and a camera at (rx, ry, rz) relative to the section origin. */
	public int[] runs(int i, double rx, double ry, double rz) {
		dev.basalt.client.compactvertex.FacingInfo facing = this.facings[i];
		if (this.runCache == null) {
			this.runCache = new int[this.count * 64][];
		}
		int pattern = 0;
		for (int g = 0; g < 6; g++) {
			if (facing.hidden(g, rx, ry, rz)) {
				pattern |= 1 << g;
			}
		}
		int slot = i * 64 + pattern;
		int[] runs = this.runCache[slot];
		if (runs == null) {
			runs = computeRuns(facing, this.firstIndex[i], rx, ry, rz);
			this.runCache[slot] = runs;
		}
		if (VERIFY_RUNS) {
			int[] exact = computeRuns(facing, this.firstIndex[i], rx, ry, rz);
			if (!java.util.Arrays.equals(exact, runs)) {
				throw new IllegalStateException("basalt: cached face-cull runs differ " + java.util.Arrays.toString(runs) + " vs " + java.util.Arrays.toString(exact));
			}
			RUNS_VERIFIED++;
		}
		return runs;
	}

	/** Skips direction groups facing away from the camera and merges runs of visible groups. */
	private static int[] computeRuns(dev.basalt.client.compactvertex.FacingInfo facing, int firstIndex, double rx, double ry, double rz) {
		int[] out = new int[16];
		int n = 0;
		int runStart = -1, quad = 0, runQuad = 0;
		for (int g = 0; g <= 7; g++) {
			boolean visible = g < 7 && facing.quadCounts()[g] > 0 && !facing.hidden(g, rx, ry, rz);
			if (visible && runStart < 0) {
				runStart = g;
				runQuad = quad;
			} else if (!visible && runStart >= 0 && (g == 7 || facing.quadCounts()[g] > 0)) {
				out[n++] = (quad - runQuad) * 6;
				out[n++] = firstIndex + runQuad * 6;
				runStart = -1;
			}
			if (g < 7) {
				quad += facing.quadCounts()[g];
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	private DrawCache(SectionRenderDispatcher owner, SectionMesh mesh) {
		this.owner = owner;
		int n = 0;
		boolean complete = true;
		ChunkSectionLayer[] layers = new ChunkSectionLayer[LAYERS.length];
		int[] indexCount = new int[LAYERS.length];
		int[] firstIndex = new int[LAYERS.length];
		int[] baseVertex = new int[LAYERS.length];
		int[] groupHash = new int[LAYERS.length];
		boolean[] custom = new boolean[LAYERS.length];
		GpuBuffer[] vertexBuffers = new GpuBuffer[LAYERS.length];
		GpuBuffer[] indexBuffers = new GpuBuffer[LAYERS.length];
		IndexType[] indexTypes = new IndexType[LAYERS.length];
		dev.basalt.client.compactvertex.FacingInfo[] facings = new dev.basalt.client.compactvertex.FacingInfo[LAYERS.length];

		for (ChunkSectionLayer layer : LAYERS) {
			SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
			if (draw == null) {
				continue;
			}
			SectionRenderDispatcher.RenderSectionBufferSlice slice = owner.getRenderSectionSlice(mesh, layer);
			if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
				complete = false;
				continue;
			}
			// Same arithmetic as vanilla LevelRenderer.extractSectionDrawGroups.
			int hash = 31 * 173 + slice.vertexBuffer().hashCode();
			int first = 0;
			if (draw.hasCustomIndexBuffer()) {
				IndexType type = draw.indexType();
				hash = 31 * hash + slice.indexBuffer().hashCode();
				hash = 31 * hash + type.hashCode();
				first = (int) (slice.indexBufferOffset() / type.bytes);
				indexBuffers[n] = slice.indexBuffer();
				indexTypes[n] = type;
			}
			layers[n] = layer;
			indexCount[n] = draw.indexCount();
			firstIndex[n] = first;
			baseVertex[n] = (int) (slice.vertexBufferOffset() / layer.pipeline(false).getVertexFormatBinding(0).getVertexSize());
			groupHash[n] = hash;
			custom[n] = draw.hasCustomIndexBuffer();
			vertexBuffers[n] = slice.vertexBuffer();
			if (!VERIFY && !draw.hasCustomIndexBuffer() && mesh instanceof dev.basalt.client.compactvertex.FacingHolder fh) {
				dev.basalt.client.compactvertex.FacingInfo fi = fh.basalt$facing(layer.ordinal());
				// only trust it if it describes exactly this draw's quads
				if (fi != null && fi.startQuad(7) * 6 == draw.indexCount()) {
					facings[n] = fi;
				}
			}
			n++;
		}

		this.complete = complete;
		this.count = n;
		this.layers = layers;
		this.indexCount = indexCount;
		this.firstIndex = firstIndex;
		this.baseVertex = baseVertex;
		this.groupHash = groupHash;
		this.customIndices = custom;
		this.vertexBuffers = vertexBuffers;
		this.indexBuffers = indexBuffers;
		this.indexTypes = indexTypes;
		this.facings = facings;
	}

	public static DrawCache get(SectionRenderDispatcher dispatcher, SectionMesh mesh) {
		if (mesh instanceof DrawCacheHolder holder) {
			DrawCache cache = holder.basalt$drawCache();
			if (cache != null && cache.owner == dispatcher) {
				return cache;
			}
			cache = new DrawCache(dispatcher, mesh);
			if (cache.complete) {
				holder.basalt$setDrawCache(cache);
			}
			return cache;
		}
		return new DrawCache(dispatcher, mesh);
	}

	public static void invalidate(Object mesh) {
		if (mesh instanceof DrawCacheHolder holder) {
			holder.basalt$setDrawCache(null);
		}
		EPOCH.incrementAndGet();
	}

	/**
	 * Bumped whenever anything a frame's draw groups depend on may have changed: a GPU allocation,
	 * a section's mesh, or a section's position. Equal epochs + identical visible list = identical output.
	 */
	public static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();
}
