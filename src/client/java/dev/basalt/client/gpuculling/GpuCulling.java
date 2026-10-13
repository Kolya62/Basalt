package dev.basalt.client.gpuculling;

import static org.lwjgl.vulkan.VK12.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import dev.basalt.client.drawcache.DrawCache;
import dev.basalt.client.mixin.gpuculling.ChunkSectionsToRenderAccess;
import dev.basalt.client.mixin.gpuculling.DrawIndirectAccess;
import dev.basalt.client.mixin.gpuculling.FrontendCommandEncoderAccess;
import dev.basalt.client.mixin.gpuculling.FrontendGpuDeviceAccess;
import dev.basalt.client.mixin.gpuculling.VulkanCommandEncoderAccess;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

/**
 * Two-phase GPU occlusion culling of opaque chunk sections:
 * <ol>
 * <li>phase 1 (vanilla pass): sections not known to be occluded, plus entities;</li>
 * <li>between passes: build a Hi-Z (min, reversed-Z) pyramid from that depth, test every
 * section's box against it, zero the indirect commands of still-hidden phase-2 sections;</li>
 * <li>phase 2 (new pass): draw the remaining phase-2 sections - visible ones appear in the same frame.</li>
 * </ol>
 * Results feed back (3 frames later) into which sections go to phase 2. Any failure disables it.
 */
public final class GpuCulling {
	/** "true" / "false" force it; "auto" enables it from render distance AUTO_MIN_RD up (measured break-even ~20). */
	private static final String MODE = BasaltConfig.get("gpuculling", "auto");
	private static final int AUTO_MIN_RD = 24;
	public static final Matrix4f PROJECTION = new Matrix4f();

	/** Benchmark hook: GPU timestamps around the culling compute (start = true). */
	public static java.util.function.Consumer<Boolean> TIMING;
	private static CullGpu gpu;
	private static boolean failed;
	private static long frame;
	private static boolean splitThisFrame;

	// filled by the draw-group extraction of the frame (kept across reused frames)
	private static Object[] sectionRefs = new Object[0];
	private static int[] sectionOrigins = new int[0];
	private static int sectionCount;
	private static final Set<LevelRenderer.ChunkDrawGroup> PHASE2 = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final EnumMap<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> ORDER = new EnumMap<>(ChunkSectionLayer.class);

	// per prepared frame
	private static ChunkSectionsToRender.DrawIndirect frameDraws;
	private static final EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> PHASE2_DRAWS = new EnumMap<>(ChunkSectionLayer.class);
	private static final Matrix4f VIEW_PROJ = new Matrix4f();
	private static double camX, camY, camZ;
	private static boolean prepared;

	private static final Object[][] SLOT_REFS = new Object[CullGpu.SLOTS][];
	private static final int[] SLOT_COUNT = new int[CullGpu.SLOTS];
	private static final boolean[] SLOT_VALID = new boolean[CullGpu.SLOTS];

	private GpuCulling() {
	}

	private static int slot() {
		return (int) (frame % CullGpu.SLOTS);
	}

	/** Start of draw-group extraction: consume old results, decide whether this frame splits. */
	public static boolean beginFrame() {
		frame++;
		prepared = false;
		splitThisFrame = false;
		if (failed || !enabledNow()) {
			return false;
		}
		try {
			if (gpu == null) {
				if (!(((FrontendGpuDeviceAccess) RenderSystem.getDevice()).basalt$backend() instanceof VulkanDevice device)) {
					failed = true;
					return false;
				}
				// Results are read back SLOTS frames later without a fence: that is only safe while the
				// backend never has SLOTS or more submits in flight (renderpearl waits for submit N-2).
				if (VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT >= CullGpu.SLOTS) {
					Basalt.LOGGER.warn("[gpuculling] backend allows {} submits in flight, need fewer than {}; disabled",
						VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT, CullGpu.SLOTS);
					failed = true;
					return false;
				}
				gpu = new CullGpu(device);
				Basalt.LOGGER.info("[gpuculling] initialised");
			}
			int s = slot();
			if (SLOT_VALID[s]) {
				readback(s);
				SLOT_VALID[s] = false;
			}
			gpu.resetSlot(s);
			splitThisFrame = true;
		} catch (Throwable t) {
			disable("init", t);
		}
		return splitThisFrame;
	}

	private static int loggedRd = -1;

	private static boolean enabledNow() {
		int rd = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance();
		if (rd != loggedRd) {
			loggedRd = rd;
			Basalt.LOGGER.info("[gpuculling] mode {}, effective render distance {}", MODE, rd);
		}
		return switch (MODE) {
			case "true" -> true;
			case "auto" -> net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() >= AUTO_MIN_RD;
			default -> false;
		};
	}

	private static void readback(int s) {
		long address = gpu.visibleAddress(s);
		Object[] refs = SLOT_REFS[s];
		boolean changed = false;
		for (int k = 0; k < SLOT_COUNT[s]; k++) {
			boolean occluded = MemoryUtil.memGetInt(address + 4L * k) == 0;
			CullState state = (CullState) refs[k];
			if (state.basalt$occluded() != occluded) {
				state.basalt$setOccluded(occluded);
				changed = true;
			}
		}
		if (changed) {
			DrawCache.EPOCH.incrementAndGet(); // phase assignment changed: no draw-group reuse
		}
	}

	public static boolean isPhase2(SectionRenderDispatcher.RenderSection section, ChunkSectionLayer layer) {
		return splitThisFrame && !layer.translucent() && ((CullState) section).basalt$occluded();
	}

	/** Fresh extraction results (a reused frame keeps the previous ones, which are then identical). */
	public static void captureExtraction(Object[] refs, int[] origins, int count, Set<LevelRenderer.ChunkDrawGroup> phase2,
		Map<ChunkSectionLayer, List<LevelRenderer.ChunkDrawGroup>> groups) {
		if (!splitThisFrame) {
			// only a split frame uses these; don't keep the sections reachable otherwise
			if (sectionCount != 0) {
				reset();
			}
			return;
		}
		sectionRefs = refs;
		sectionOrigins = origins;
		sectionCount = count;
		PHASE2.clear();
		PHASE2.addAll(phase2);
		ORDER.clear();
		groups.forEach((layer, list) -> ORDER.put(layer, new ArrayList<>(list)));
	}

	/** After prepareChunkRendersIndirect: move phase-2 groups out of the vanilla draw lists. */
	public static void onPrepared(ChunkSectionsToRender result, Matrix4fc modelView, double cx, double cy, double cz) {
		if (!splitThisFrame || !(result instanceof ChunkSectionsToRender.DrawIndirect draws) || sectionCount > CullGpu.MAX_SECTIONS) {
			splitThisFrame = false;
			return;
		}
		try {
			EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> all = ((DrawIndirectAccess) (Object) draws).basalt$groups();
			PHASE2_DRAWS.clear();
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> phase2 = new ArrayList<>();
				PHASE2_DRAWS.put(layer, phase2);
				if (layer.translucent()) {
					continue;
				}
				List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> list = all.get(layer);
				List<LevelRenderer.ChunkDrawGroup> order = ORDER.get(layer);
				if (order == null || order.size() != list.size()) {
					throw new IllegalStateException("draw group order mismatch for " + layer);
				}
				List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> phase1 = new ArrayList<>();
				for (int i = 0; i < list.size(); i++) {
					(PHASE2.contains(order.get(i)) ? phase2 : phase1).add(list.get(i));
				}
				all.put(layer, phase1);
			}
			frameDraws = draws;
			camX = cx;
			camY = cy;
			camZ = cz;
			VIEW_PROJ.set(PROJECTION).mul(modelView);
			int s = slot();
			long p = gpu.sectionsAddress(s);
			for (int k = 0; k < sectionCount; k++) {
				MemoryUtil.memPutFloat(p, (float) (sectionOrigins[k * 3] - cx));
				MemoryUtil.memPutFloat(p + 4, (float) (sectionOrigins[k * 3 + 1] - cy));
				MemoryUtil.memPutFloat(p + 8, (float) (sectionOrigins[k * 3 + 2] - cz));
				p += 16;
			}
			SLOT_REFS[s] = java.util.Arrays.copyOf(sectionRefs, sectionCount);
			SLOT_COUNT[s] = sectionCount;
			prepared = true;
		} catch (Throwable t) {
			disable("prepare", t);
		}
	}

	public static boolean wantsSplit() {
		return prepared;
	}

	/** Called after the opaque phase with the splittable main pass. */
	public static void afterOpaque(SplittablePass pass, GpuSampler sampler, GpuTextureView atlas) {
		if (!prepared) {
			return;
		}
		prepared = false;
		boolean[] culled = {false};
		RenderPass pass2 = pass.split(() -> {
			if (TIMING != null) {
				TIMING.accept(true);
			}
			culled[0] = record(pass.depth);
			if (TIMING != null) {
				TIMING.accept(false);
			}
		});
		// Phase 2: drawn even if culling failed (commands then still have instanceCount = 1).
		ChunkSectionsToRenderAccess acc = (ChunkSectionsToRenderAccess) (Object) frameDraws;
		ChunkSectionsToRender.DrawIndirect phase2 = new ChunkSectionsToRender.DrawIndirect(acc.basalt$terrainTransform(), PHASE2_DRAWS,
			acc.basalt$maxIndices(), ((DrawIndirectAccess) (Object) frameDraws).basalt$sectionInfos());
		phase2.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass2, sampler, atlas, false);
		if (culled[0]) {
			SLOT_VALID[slot()] = true;
		}
	}

	private static boolean record(GpuTextureView depthView) {
		if (failed) {
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanGpuTexture depth = ((VulkanGpuTextureView) depthView).texture();
			gpu.ensureHiz(depth.vkImage(), depth.getWidth(0), depth.getHeight(0), VulkanConst.toVk(depth.getFormat()));
			VulkanCommandEncoder encoder = (VulkanCommandEncoder) ((FrontendCommandEncoderAccess) RenderSystem.getDevice().createCommandEncoder()).basalt$backend();
			VkCommandBuffer cb = ((VulkanCommandEncoderAccess) encoder).basalt$commandBuffer();
			int s = slot();
			VulkanCommandEncoder.memoryBarrier(cb, stack);
			if (!gpu.hizInitialised) {
				VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
					.newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(gpu.hizImage)
					.dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
				b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(gpu.levels).layerCount(1);
				vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, b);
				gpu.hizInitialised = true;
			}
			// Hi-Z level 0 from depth, then the rest of the chain
			ByteBuffer push = stack.malloc(80);
			long set = gpu.allocSet(s, gpu.hizInit);
			gpu.writeImage(set, 0, gpu.depthView, true);
			gpu.writeImage(set, 1, gpu.hizMipView[0], false);
			dispatch(cb, stack, gpu.hizInit, set, push.clear().putInt(gpu.depthW).putInt(gpu.depthH).putInt(gpu.hizW).putInt(gpu.hizH).flip(),
				(gpu.hizW + 7) / 8, (gpu.hizH + 7) / 8);
			int w = gpu.hizW, h = gpu.hizH;
			for (int l = 1; l < gpu.levels; l++) {
				computeBarrier(cb, stack);
				int nw = Math.max(1, (w + 1) / 2), nh = Math.max(1, (h + 1) / 2);
				long ds = gpu.allocSet(s, gpu.hizDown);
				gpu.writeImage(ds, 0, gpu.hizMipView[l - 1], false);
				gpu.writeImage(ds, 1, gpu.hizMipView[l], false);
				dispatch(cb, stack, gpu.hizDown, ds, push.clear().putInt(w).putInt(h).putInt(nw).putInt(nh).flip(), (nw + 7) / 8, (nh + 7) / 8);
				w = nw;
				h = nh;
			}
			computeBarrier(cb, stack);
			// test every section
			long cs = gpu.allocSet(s, gpu.cull);
			gpu.writeImage(cs, 0, gpu.hizAllView, true);
			gpu.writeBuffer(cs, 1, gpu.sectionBufferHandle(s), 0, Math.max(16L, 16L * SLOT_COUNT[s]));
			gpu.writeBuffer(cs, 2, gpu.visibleBufferHandle(s), 0, Math.max(4L, 4L * SLOT_COUNT[s]));
			push.clear();
			VIEW_PROJ.get(push);
			push.position(64);
			push.putInt(gpu.hizW).putInt(gpu.hizH).putInt(gpu.levels).putInt(SLOT_COUNT[s]).flip();
			dispatch(cb, stack, gpu.cull, cs, push, (SLOT_COUNT[s] + 63) / 64, 1);
			computeBarrier(cb, stack);
			hostReadBarrier(cb, stack); // the CPU reads these results back SLOTS frames later
			// zero phase-2 commands of occluded sections, in place
			Map<GpuBuffer, Long> sets = new IdentityHashMap<>();
			for (List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> draws : PHASE2_DRAWS.values()) {
				for (ChunkSectionsToRender.GpuMultiDrawIndexedIndirect d : draws) {
					if (d.drawCount() == 0) {
						continue;
					}
					GpuBuffer commands = d.indirectCommandBuffer().buffer();
					long ps = sets.computeIfAbsent(commands, b -> {
						long ns = gpu.allocSet(s, gpu.patch);
						gpu.writeBuffer(ns, 0, gpu.visibleBufferHandle(s), 0, Math.max(4L, 4L * SLOT_COUNT[s]));
						gpu.writeBuffer(ns, 1, ((VulkanGpuBuffer) b).vkBuffer(), 0, VK_WHOLE_SIZE);
						return ns;
					});
					push.clear().putInt((int) (d.indirectCommandBuffer().offset() / 4)).putInt(d.drawCount()).flip();
					dispatch(cb, stack, gpu.patch, ps, push, (d.drawCount() + 63) / 64, 1);
				}
			}
			VulkanCommandEncoder.memoryBarrier(cb, stack);
			return true;
		} catch (Throwable t) {
			disable("record", t);
			return false;
		}
	}

	/** Compute writes -> compute reads only (cheaper than the game's ALL_COMMANDS barrier). */
	private static void computeBarrier(VkCommandBuffer cb, MemoryStack stack) {
		org.lwjgl.vulkan.VkMemoryBarrier.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
			.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, b, null, null);
	}

	/** Compute writes -> host reads, so the readback sees them once the submit has completed. */
	private static void hostReadBarrier(VkCommandBuffer cb, MemoryStack stack) {
		org.lwjgl.vulkan.VkMemoryBarrier.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
			.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, b, null, null);
	}

	private static void dispatch(VkCommandBuffer cb, MemoryStack stack, CullGpu.Pipe pipe, long set, ByteBuffer push, int gx, int gy) {
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.pipeline());
		vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.layout(), 0, stack.longs(set), null);
		vkCmdPushConstants(cb, pipe.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(cb, Math.max(1, gx), Math.max(1, gy), 1);
	}

	/** Level changed (or left): drop every reference to the old level's sections and draw groups. */
	public static void reset() {
		sectionRefs = new Object[0];
		sectionOrigins = new int[0];
		sectionCount = 0;
		PHASE2.clear();
		ORDER.clear();
		PHASE2_DRAWS.clear();
		frameDraws = null;
		prepared = false;
		splitThisFrame = false;
		java.util.Arrays.fill(SLOT_REFS, null);
		java.util.Arrays.fill(SLOT_COUNT, 0);
		java.util.Arrays.fill(SLOT_VALID, false);
		DrawCache.EPOCH.incrementAndGet(); // the next frame must extract fresh (a reused one would need ORDER/PHASE2)
	}

	private static void disable(String where, Throwable t) {
		failed = true;
		splitThisFrame = false;
		prepared = false;
		Basalt.LOGGER.error("[gpuculling] disabled after failure in {}", where, t);
	}
}
