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

	/**
	 * Appends entry i's draws for a camera at (rx, ry, rz) relative to the section origin: direction
	 * groups facing away from the camera are skipped and runs of visible groups are merged. Computed
	 * on the fly (seven group checks) straight into the draw list: a per-mesh cache of all 64 hidden
	 * patterns cost far more memory, and cache misses, than it saved.
	 */
	public void addRuns(int i, double rx, double ry, double rz, PackedDraws draws, int baseInstance) {
		dev.basalt.client.compactvertex.FacingInfo facing = this.facings[i];
		int[] counts = facing.quadCounts();
		int first = this.firstIndex[i], base = this.baseVertex[i];
		int quad = 0, runQuad = -1;
		for (int g = 0; g <= 7; g++) {
			boolean visible = g < 7 && counts[g] > 0 && !facing.hidden(g, rx, ry, rz);
			if (visible && runQuad < 0) {
				runQuad = quad;
			} else if (!visible && runQuad >= 0 && (g == 7 || counts[g] > 0)) {
				// empty groups neither start nor end a run, so runs merge across them
				draws.add((quad - runQuad) * 6, 1, first + runQuad * 6, base, baseInstance);
				runQuad = -1;
			}
			if (g < 7) {
				quad += counts[g];
			}
		}
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
