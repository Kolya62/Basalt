package dev.basalt.client.mixin.drawcache;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.client.drawcache.DrawCache;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow @Final private OptionsRenderState optionsRenderState;
	@Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
	@Shadow @Final private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;

	@WrapMethod(method = "extractSectionDrawGroups")
	private int basalt$extractCached(
		boolean respectTranslucentOrder, List<ChunkSectionInfo> sectionInfos, Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> drawGroups,
		Operation<Integer> original
	) {
		if (DrawCache.VERIFY) {
			List<ChunkSectionInfo> expectedInfos = new ArrayList<>();
			Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> expectedGroups = new EnumMap<>(ChunkSectionLayer.class);
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				expectedGroups.put(layer, new ReferenceArrayList<>());
			}
			int expected = original.call(respectTranslucentOrder, expectedInfos, expectedGroups);
			int actual = this.basalt$extract(respectTranslucentOrder, sectionInfos, drawGroups);
			basalt$check(expected == actual, "largestIndexCount", expected, actual);
			if (!basalt$sameGroups(expectedGroups, drawGroups)) {
				basalt$check(false, "drawGroups", basalt$firstDiff(expectedGroups), basalt$firstDiff2(expectedGroups, drawGroups));
			}
			basalt$check(expectedInfos.size() == sectionInfos.size(), "sectionInfos.size", expectedInfos.size(), sectionInfos.size());
			for (int i = 0; i < sectionInfos.size(); i++) {
				ChunkSectionInfo e = expectedInfos.get(i);
				ChunkSectionInfo a = sectionInfos.get(i);
				// visibility depends on wall-clock millis, which can tick between the two passes
				basalt$check(e.x() == a.x() && e.y() == a.y() && e.z() == a.z() && Math.abs(e.visibility() - a.visibility()) < 0.05f, "sectionInfo", e, a);
			}
			DrawCache.verifiedFrames++;
			return actual;
		}
		return this.basalt$extract(respectTranslucentOrder, sectionInfos, drawGroups);
	}

	/** Element-wise equals; fastutil's ReferenceArrayList.equals compares elements by identity. */
	@Unique
	private static boolean basalt$sameGroups(Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> e, Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> a) {
		for (ChunkSectionLayer l : ChunkSectionLayer.values()) {
			if (!new ArrayList<>(e.get(l)).equals(new ArrayList<>(a.get(l)))) {
				return false;
			}
		}
		return true;
	}

	@Unique
	private static String basalt$firstDiff(Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> m) {
		StringBuilder sb = new StringBuilder();
		m.forEach((l, g) -> sb.append(l).append(':').append(g.size()).append(' '));
		return sb.toString();
	}

	@Unique
	private static String basalt$firstDiff2(Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> e, Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> a) {
		StringBuilder sb = new StringBuilder(basalt$firstDiff(a));
		for (ChunkSectionLayer l : ChunkSectionLayer.values()) {
			List<LevelRenderer.ChunkDrawGroup> ge = e.get(l), ga = a.get(l);
			for (int g = 0; g < Math.min(ge.size(), ga.size()); g++) {
				LevelRenderer.ChunkDrawGroup x = ge.get(g), y = ga.get(g);
				if (!x.equals(y)) {
					sb.append("\n layer ").append(l).append(" group ").append(g)
						.append(" vb ").append(x.vertexBuffer().equals(y.vertexBuffer())).append(" ib ").append(java.util.Objects.equals(x.indexBuffer(), y.indexBuffer()))
						.append(" it ").append(x.indexType() == y.indexType()).append(" draws ").append(x.draws().size()).append('/').append(y.draws().size());
					for (int d = 0; d < Math.min(x.draws().size(), y.draws().size()); d++) {
						if (!x.draws().get(d).equals(y.draws().get(d))) {
							sb.append("\n  draw ").append(d).append(' ').append(x.draws().get(d)).append(" vs ").append(y.draws().get(d));
							break;
						}
					}
					break;
				}
			}
		}
		return sb.toString();
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "prepareChunkRendersIndirect", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
	private void basalt$afterPrepare(org.joml.Matrix4fc modelView, boolean respectTranslucentOrder,
		org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.client.renderer.chunk.ChunkSectionsToRender> cir) {
		net.minecraft.world.phys.Vec3 c = this.levelRenderState.cameraRenderState.pos;
		dev.basalt.client.gpuculling.GpuCulling.onPrepared(cir.getReturnValue(), modelView, c.x(), c.y(), c.z());
	}

	@Unique
	private static void basalt$check(boolean ok, String what, Object expected, Object actual) {
		if (!ok) {
			throw new IllegalStateException("Basalt drawcache mismatch in " + what + ": expected " + expected + ", got " + actual);
		}
	}

	/** -Dbasalt.drawcache.reuse=false disables previous-frame reuse (for A/B tests). */
	@Unique private static final boolean BASALT_REUSE = !"false".equals(System.getProperty("basalt.drawcache.reuse"));
	// Previous frame's result, reused verbatim when nothing it depends on changed (static camera).
	@Unique private Object[] basalt$lastSections = new Object[0];
	@Unique private int basalt$lastCount = -1;
	@Unique private long basalt$lastEpoch = -1;
	@Unique private boolean basalt$lastRespect;
	@Unique private long basalt$lastFade = -1;
	@Unique private SectionRenderDispatcher basalt$lastDispatcher;
	@Unique private boolean basalt$lastFullyVisible;
	@Unique private boolean basalt$lastSplit;
	@Unique private double basalt$lastCamX = Double.NaN, basalt$lastCamY, basalt$lastCamZ;
	@Unique private int basalt$lastLargest;
	@Unique private final List<ChunkSectionInfo> basalt$lastInfos = new ArrayList<>();
	@Unique private Object[] basalt$refs = new Object[0];
	@Unique private int[] basalt$origins = new int[0];
	@Unique private final EnumMap<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> basalt$lastGroups = new EnumMap<>(ChunkSectionLayer.class);

	@Unique
	private boolean basalt$sameCamera() {
		net.minecraft.world.phys.Vec3 c = this.levelRenderState.cameraRenderState.pos;
		return c.x() == this.basalt$lastCamX && c.y() == this.basalt$lastCamY && c.z() == this.basalt$lastCamZ;
	}

	/** fastutil keeps an Object[] internally; read it through the erased type to avoid a generic array cast. */
	@Unique
	@SuppressWarnings("unchecked")
	private static Object[] basalt$elements(ObjectArrayList<?> list) {
		return ((ObjectArrayList<Object>) list).elements();
	}

	@Unique
	private boolean basalt$canReuse(long epoch, boolean respect, long fade, SectionRenderDispatcher dispatcher) {
		int count = this.visibleSections.size();
		if (!this.basalt$lastFullyVisible || epoch != this.basalt$lastEpoch || respect != this.basalt$lastRespect || fade != this.basalt$lastFade
			|| dispatcher != this.basalt$lastDispatcher || count != this.basalt$lastCount || !this.basalt$sameCamera()) {
			return false;
		}
		Object[] now = basalt$elements(this.visibleSections);
		Object[] last = this.basalt$lastSections;
		for (int i = 0; i < count; i++) {
			if (now[i] != last[i]) {
				return false;
			}
		}
		return true;
	}

	/** Same output as vanilla extractSectionDrawGroups, reading per-mesh data from {@link DrawCache}. */
	@Unique
	private int basalt$extract(
		boolean respectTranslucentOrder, List<ChunkSectionInfo> sectionInfos, Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> drawGroups
	) {
		SectionRenderDispatcher dispatcher = this.sectionRenderDispatcher;
		if (dispatcher == null) {
			return 0;
		}
		boolean split = !DrawCache.VERIFY && dev.basalt.client.gpuculling.GpuCulling.beginFrame();
		long epoch = DrawCache.EPOCH.get();
		long fade = Util.toMillis(this.optionsRenderState.chunkSectionFadeInTime);
		if (BASALT_REUSE && split == this.basalt$lastSplit && this.basalt$canReuse(epoch, respectTranslucentOrder, fade, dispatcher)) {
			sectionInfos.addAll(this.basalt$lastInfos);
			this.basalt$lastGroups.forEach((layer, groups) -> drawGroups.get(layer).addAll(groups));
			return this.basalt$lastLargest;
		}
		// last frame's lists are about to be replaced: hand their arrays to this frame's lists
		this.basalt$lastGroups.forEach((layer, groups) -> {
			for (LevelRenderer.ChunkDrawGroup group : groups) {
				if (group.draws() instanceof dev.basalt.client.drawcache.PackedDraws packed) {
					packed.recycle();
				}
			}
		});
		this.basalt$lastGroups.clear();
		int largest = this.basalt$extractFresh(respectTranslucentOrder, sectionInfos, drawGroups, dispatcher);
		// remember for next frame; only worth/allowed once every section finished fading in
		boolean fullyVisible = true;
		for (ChunkSectionInfo info : sectionInfos) {
			if (info.visibility() < 1.0F) {
				fullyVisible = false;
				break;
			}
		}
		this.basalt$lastFullyVisible = fullyVisible && DrawCache.EPOCH.get() == epoch;
		this.basalt$lastSplit = split;
		this.basalt$lastEpoch = epoch;
		this.basalt$lastRespect = respectTranslucentOrder;
		this.basalt$lastFade = fade;
		this.basalt$lastDispatcher = dispatcher;
		this.basalt$lastCount = this.visibleSections.size();
		this.basalt$lastSections = java.util.Arrays.copyOf(basalt$elements(this.visibleSections), this.basalt$lastCount);
		this.basalt$lastLargest = largest;
		net.minecraft.world.phys.Vec3 c = this.levelRenderState.cameraRenderState.pos;
		this.basalt$lastCamX = c.x();
		this.basalt$lastCamY = c.y();
		this.basalt$lastCamZ = c.z();
		this.basalt$lastInfos.clear();
		this.basalt$lastInfos.addAll(sectionInfos);
		this.basalt$lastGroups.clear();
		drawGroups.forEach((layer, groups) -> this.basalt$lastGroups.put(layer, new ArrayList<>(groups)));
		return largest;
	}

	@Unique
	private int basalt$extractFresh(
		boolean respectTranslucentOrder, List<ChunkSectionInfo> sectionInfos, Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> drawGroups,
		SectionRenderDispatcher dispatcher
	) {
		int largestIndexCount = 0;
		net.minecraft.world.phys.Vec3 cam = this.levelRenderState.cameraRenderState.pos;
		double camX = cam.x(), camY = cam.y(), camZ = cam.z();
		Int2ObjectOpenHashMap<LevelRenderer.ChunkDrawGroup> drawGroupCache = new Int2ObjectOpenHashMap<>();
		dispatcher.lock();
		try {
			long fadeDuration = Util.toMillis(this.optionsRenderState.chunkSectionFadeInTime);
			long now = Util.getMillis();
			int lastTransparentGroupHash = 0;
			// Consecutive sections usually share a GPU buffer, i.e. the same group hash. Remember the last
			// (hash, group) per layer to skip most map lookups; kept equal to drawGroupCache.get(hash)
			// by updating it on every put below.
			int layerCount = ChunkSectionLayer.values().length;
			// index layer * 2 + phase: phase-2 (GPU-culled) sections get their own draw groups
			int[] lastHash = new int[layerCount * 2];
			LevelRenderer.ChunkDrawGroup[] lastGroup = new LevelRenderer.ChunkDrawGroup[layerCount * 2];
			Object[] refs = new Object[this.visibleSections.size()];
			int[] origins = new int[this.visibleSections.size() * 3];
			java.util.Set<LevelRenderer.ChunkDrawGroup> phase2Groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			Object[] sections = basalt$elements(this.visibleSections);
			int sectionCount = this.visibleSections.size();

			for (int s = 0; s < sectionCount; s++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) sections[s];
				DrawCache cache = DrawCache.get(dispatcher, section.getSectionMesh());
				int count = cache.count;
				if (count == 0) {
					continue;
				}
				BlockPos origin = section.getRenderOrigin();
				int sectionInfoIndex = sectionInfos.size();
				sectionInfos.add(cache.sectionInfo(origin.getX(), origin.getY(), origin.getZ(), section.getVisibility(now, fadeDuration)));
				refs[sectionInfoIndex] = section;
				origins[sectionInfoIndex * 3] = origin.getX();
				origins[sectionInfoIndex * 3 + 1] = origin.getY();
				origins[sectionInfoIndex * 3 + 2] = origin.getZ();

				for (int i = 0; i < count; i++) {
					ChunkSectionLayer layer = cache.layers[i];
					boolean phase2 = dev.basalt.client.gpuculling.GpuCulling.isPhase2(section, layer);
					int hash = phase2 ? cache.groupHash[i] ^ 0x5bd1e995 : cache.groupHash[i];
					if (!cache.customIndices[i] && cache.indexCount[i] > largestIndexCount) {
						largestIndexCount = cache.indexCount[i];
					}

					LevelRenderer.ChunkDrawGroup group = null;
					if (layer.translucent() && respectTranslucentOrder) {
						if (hash == lastTransparentGroupHash) {
							group = drawGroups.get(layer).getLast();
						}
						lastTransparentGroupHash = hash;
					} else {
						int li = layer.ordinal() * 2 + (phase2 ? 1 : 0);
						if (lastGroup[li] != null && lastHash[li] == hash) {
							group = lastGroup[li];
						} else {
							group = drawGroupCache.get(hash);
							if (group != null) {
								lastHash[li] = hash;
								lastGroup[li] = group;
							}
						}
					}

					if (group == null) {
						var indexBuffer = cache.indexBuffers[i];
						group = new LevelRenderer.ChunkDrawGroup(
							cache.vertexBuffers[i].slice(), indexBuffer != null ? indexBuffer.slice() : null, cache.indexTypes[i], new dev.basalt.client.drawcache.PackedDraws()
						);
						drawGroupCache.put(hash, group);
						for (int l = 0; l < layerCount * 2; l++) {
							if (lastGroup[l] != null && lastHash[l] == hash) {
								lastGroup[l] = group; // mirror the map overwrite
							}
						}
						if (!(layer.translucent() && respectTranslucentOrder)) {
							lastHash[layer.ordinal() * 2 + (phase2 ? 1 : 0)] = hash;
							lastGroup[layer.ordinal() * 2 + (phase2 ? 1 : 0)] = group;
						}
						if (phase2) {
							phase2Groups.add(group);
						}
						drawGroups.get(layer).add(group);
					}

					dev.basalt.client.compactvertex.FacingInfo facing = cache.facings[i];
					if (facing == null) {
						((dev.basalt.client.drawcache.PackedDraws) group.draws()).add(cache.indexCount[i], 1, cache.firstIndex[i], cache.baseVertex[i], sectionInfoIndex);
					} else {
						int[] runs = cache.runs(i, camX - origin.getX(), camY - origin.getY(), camZ - origin.getZ());
						dev.basalt.client.drawcache.PackedDraws draws = (dev.basalt.client.drawcache.PackedDraws) group.draws();
						for (int r = 0; r < runs.length; r += 2) {
							draws.add(runs[r], 1, runs[r + 1], cache.baseVertex[i], sectionInfoIndex);
						}
					}
				}
			}
			dev.basalt.client.gpuculling.GpuCulling.captureExtraction(refs, origins, sectionInfos.size(), phase2Groups, drawGroups);
		} finally {
			dispatcher.unlock();
		}
		return largestIndexCount;
	}
}
