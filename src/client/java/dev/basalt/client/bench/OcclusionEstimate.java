package dev.basalt.client.bench;

import dev.basalt.client.compactvertex.FacingHolder;
import dev.basalt.client.compactvertex.FacingInfo;
import dev.basalt.client.culling.OcclusionProbe;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** -Dbasalt.bench.occlusion=true: once a second, ray-test every drawn section for full occlusion. */
public final class OcclusionEstimate {
	public static final boolean ENABLED = Boolean.getBoolean("basalt.bench.occlusion");
	private static long lastNs, samples, sections, occluded, quads, occludedQuads;

	private OcclusionEstimate() {
	}

	public static void tick(Minecraft mc, long now, Iterable<SectionRenderDispatcher.RenderSection> visible) {
		if (!ENABLED || now - lastNs < 1_000_000_000L || mc.level == null) {
			return;
		}
		lastNs = now;
		samples++;
		Vec3 cam = mc.player.getEyePosition();
		for (SectionRenderDispatcher.RenderSection s : visible) {
			SectionMesh mesh = s.getSectionMesh();
			int q = 0;
			if (mesh instanceof FacingHolder fh) {
				for (int l = 0; l < 3; l++) {
					FacingInfo fi = fh.basalt$facing(l);
					if (fi != null) {
						q += fi.startQuad(7);
					}
				}
			}
			if (q == 0) {
				continue;
			}
			BlockPos o = s.getRenderOrigin();
			sections++;
			quads += q;
			if (!OcclusionProbe.visible(mc.level, cam, new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + 16, o.getY() + 16, o.getZ() + 16))) {
				occluded++;
				occludedQuads += q;
			}
		}
	}

	public static String report() {
		if (samples == 0) {
			return "";
		}
		return String.format(Locale.ROOT, "occluded_sections_pct=%.1f%noccluded_quads_pct=%.1f%n", occluded * 100.0 / sections, occludedQuads * 100.0 / quads);
	}
}
