package dev.basalt.client.compactvertex;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Compact 16-byte terrain vertex replacing vanilla's 28-byte BLOCK format for chunk meshes.
 * Position is quantised to 1/2048 block within [-8, 24) of the section origin, texture
 * coordinates to 1/65535 of the atlas; colour and light keep their full precision.
 */
public final class CompactTerrain {
	public static final VertexFormat FORMAT = VertexFormat.builder(0)
		.addAttribute("PackedPos", GpuFormat.RGBA16_UINT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("UV0", GpuFormat.RG16_UNORM)
		.build();
	public static final Identifier VANILLA_SHADER = Identifier.withDefaultNamespace("core/terrain");
	public static final Identifier SHADER = Identifier.fromNamespaceAndPath("basalt", "core/terrain_compact");

	private static final int IN_SIZE = 28;
	private static final int OUT_SIZE = 16;
	private static final ThreadLocal<ByteBuffer[]> SCRATCH = ThreadLocal.withInitial(() -> new ByteBuffer[1]);

	private CompactTerrain() {
	}

	/** -Dbasalt.facecull.sign=-1 flips the winding convention used to classify quad facing (testing). */
	private static final float WINDING_SIGN = Float.parseFloat(System.getProperty("basalt.facecull.sign", "1"));

	/**
	 * Like {@link #convert} but also regroups quads by facing (see {@link FacingInfo}). Only valid for
	 * layers drawn with the shared sequential quad index buffer (not translucent, whose indices are sorted).
	 */
	public static ByteBuffer convertSorted(ByteBuffer block, FacingInfo[] infoOut) {
		int quads = block.remaining() / (IN_SIZE * 4);
		long src = MemoryUtil.memAddress(block);
		byte[] facing = new byte[quads];
		int[] counts = new int[7];
		float[] plane = new float[14];
		for (int g = 0; g < 7; g++) {
			plane[g * 2] = Float.POSITIVE_INFINITY;
			plane[g * 2 + 1] = Float.NEGATIVE_INFINITY;
		}
		for (int q = 0; q < quads; q++) {
			long b = src + (long) q * IN_SIZE * 4;
			int f = classify(b);
			facing[q] = (byte) f;
			counts[f]++;
			if (f < 6) {
				float p = MemoryUtil.memGetFloat(b + 4L * (f >> 1 == 0 ? 1 : f >> 1 == 1 ? 2 : 0));
				plane[f * 2] = Math.min(plane[f * 2], p);
				plane[f * 2 + 1] = Math.max(plane[f * 2 + 1], p);
			}
		}
		// order quads by group, stable
		int[] next = new int[7];
		for (int g = 1; g < 7; g++) {
			next[g] = next[g - 1] + counts[g - 1];
		}
		ByteBuffer out = scratch(quads * 4);
		long dst = MemoryUtil.memAddress(out);
		for (int q = 0; q < quads; q++) {
			int slot = next[facing[q]]++;
			long from = src + (long) q * IN_SIZE * 4;
			long to = dst + (long) slot * OUT_SIZE * 4;
			for (int v = 0; v < 4; v++) {
				packVertex(from + (long) v * IN_SIZE, to + (long) v * OUT_SIZE);
			}
		}
		out.clear().limit(quads * 4 * OUT_SIZE);
		infoOut[0] = new FacingInfo(counts, plane);
		return out;
	}

	/** 0..5 = DOWN, UP, NORTH, SOUTH, WEST, EAST for axis-aligned planar quads, 6 otherwise. */
	private static int classify(long quad) {
		float x0 = MemoryUtil.memGetFloat(quad), y0 = MemoryUtil.memGetFloat(quad + 4), z0 = MemoryUtil.memGetFloat(quad + 8);
		float x1 = MemoryUtil.memGetFloat(quad + IN_SIZE), y1 = MemoryUtil.memGetFloat(quad + IN_SIZE + 4), z1 = MemoryUtil.memGetFloat(quad + IN_SIZE + 8);
		float x2 = MemoryUtil.memGetFloat(quad + 2 * IN_SIZE), y2 = MemoryUtil.memGetFloat(quad + 2 * IN_SIZE + 4), z2 = MemoryUtil.memGetFloat(quad + 2 * IN_SIZE + 8);
		float x3 = MemoryUtil.memGetFloat(quad + 3 * IN_SIZE), y3 = MemoryUtil.memGetFloat(quad + 3 * IN_SIZE + 4), z3 = MemoryUtil.memGetFloat(quad + 3 * IN_SIZE + 8);
		// normal from the quad's diagonals (robust to one degenerate corner)
		float ax = x2 - x0, ay = y2 - y0, az = z2 - z0, bx = x3 - x1, by = y3 - y1, bz = z3 - z1;
		float nx = (ay * bz - az * by) * WINDING_SIGN, ny = (az * bx - ax * bz) * WINDING_SIGN, nz = (ax * by - ay * bx) * WINDING_SIGN;
		float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
		float eps = 1e-5f;
		if (anx < eps && anz < eps && any > eps && y0 == y1 && y0 == y2 && y0 == y3) {
			return ny < 0 ? 0 : 1;
		}
		if (anx < eps && any < eps && anz > eps && z0 == z1 && z0 == z2 && z0 == z3) {
			return nz < 0 ? 2 : 3;
		}
		if (any < eps && anz < eps && anx > eps && x0 == x1 && x0 == x2 && x0 == x3) {
			return nx < 0 ? 4 : 5;
		}
		return 6;
	}

	private static ByteBuffer scratch(int vertices) {
		ByteBuffer[] holder = SCRATCH.get();
		ByteBuffer out = holder[0];
		if (out == null || out.capacity() < vertices * OUT_SIZE) {
			if (out != null) {
				MemoryUtil.memFree(out);
			}
			out = MemoryUtil.memAlloc(Math.max(vertices * OUT_SIZE, 1 << 16));
			holder[0] = out;
		}
		return out;
	}

	private static void packVertex(long src, long dst) {
		MemoryUtil.memPutShort(dst, quantPos(MemoryUtil.memGetFloat(src)));
		MemoryUtil.memPutShort(dst + 2, quantPos(MemoryUtil.memGetFloat(src + 4)));
		MemoryUtil.memPutShort(dst + 4, quantPos(MemoryUtil.memGetFloat(src + 8)));
		int light = MemoryUtil.memGetInt(src + 24);
		MemoryUtil.memPutShort(dst + 6, (short) ((light & 0xFF) | (light >>> 16 & 0xFF) << 8));
		MemoryUtil.memPutInt(dst + 8, MemoryUtil.memGetInt(src + 12));
		MemoryUtil.memPutShort(dst + 12, quantUv(MemoryUtil.memGetFloat(src + 16)));
		MemoryUtil.memPutShort(dst + 14, quantUv(MemoryUtil.memGetFloat(src + 20)));
	}

	/** Converts a BLOCK-format vertex buffer; the result is valid until the next call on this thread. */
	public static ByteBuffer convert(ByteBuffer block) {
		int vertices = block.remaining() / IN_SIZE;
		ByteBuffer out = scratch(vertices);
		long src = MemoryUtil.memAddress(block);
		long dst = MemoryUtil.memAddress(out);
		for (int i = 0; i < vertices; i++, src += IN_SIZE, dst += OUT_SIZE) {
			MemoryUtil.memPutShort(dst, quantPos(MemoryUtil.memGetFloat(src)));
			MemoryUtil.memPutShort(dst + 2, quantPos(MemoryUtil.memGetFloat(src + 4)));
			MemoryUtil.memPutShort(dst + 4, quantPos(MemoryUtil.memGetFloat(src + 8)));
			int light = MemoryUtil.memGetInt(src + 24); // block light in low 16 bits, sky light in high 16
			MemoryUtil.memPutShort(dst + 6, (short) ((light & 0xFF) | (light >>> 16 & 0xFF) << 8));
			MemoryUtil.memPutInt(dst + 8, MemoryUtil.memGetInt(src + 12)); // RGBA8 colour unchanged
			MemoryUtil.memPutShort(dst + 12, quantUv(MemoryUtil.memGetFloat(src + 16)));
			MemoryUtil.memPutShort(dst + 14, quantUv(MemoryUtil.memGetFloat(src + 20)));
		}
		out.clear().limit(vertices * OUT_SIZE);
		return out;
	}

	private static short quantPos(float v) {
		return (short) Math.clamp(Math.round((v + 8.0f) * 2048.0f), 0, 65535);
	}

	private static short quantUv(float v) {
		return (short) Math.clamp(Math.round(v * 65535.0f), 0, 65535);
	}
}
