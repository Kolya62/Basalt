package dev.basalt.client.mixin.modelpart;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.basalt.client.modelpart.BulkVertexSink;
import dev.basalt.client.modelpart.CubeIndex;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * A cube's 6 faces reference 24 vertices but only 8 distinct positions. Vanilla transforms all
 * 24 by the pose matrix every time an entity model is drawn; this transforms each distinct
 * position once. The per-position arithmetic is the same JOML call, so output is bit-identical.
 */
@Mixin(ModelPart.Cube.class)
public class CubeMixin {
	@Shadow @Final public ModelPart.Polygon[] polygons;

	@Unique private CubeIndex basalt$index;

	@Unique
	private CubeIndex basalt$buildCubeIndex() {
		int total = 0;
		for (ModelPart.Polygon polygon : this.polygons) {
			total += polygon.vertices().length;
		}
		int[] map = new int[total];
		float[] unique = new float[total * 3];
		int uniqueCount = 0;
		int v = 0;
		for (ModelPart.Polygon polygon : this.polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				float x = vertex.worldX(), y = vertex.worldY(), z = vertex.worldZ();
				int found = -1;
				for (int u = 0; u < uniqueCount; u++) {
					// exact float equality on purpose: only merge truly identical inputs
					if (unique[u * 3] == x && unique[u * 3 + 1] == y && unique[u * 3 + 2] == z) {
						found = u;
						break;
					}
				}
				if (found < 0) {
					found = uniqueCount++;
					unique[found * 3] = x;
					unique[found * 3 + 1] = y;
					unique[found * 3 + 2] = z;
				}
				map[v++] = found;
			}
		}
		return new CubeIndex(map, java.util.Arrays.copyOf(unique, uniqueCount * 3));
	}

	/** -Dbasalt.modelpart.verify=true: compare every bulk-written cube with the vanilla vertex path, byte for byte. */
	@Unique
	private static final boolean VERIFY = Boolean.getBoolean("basalt.modelpart.verify");
	@Unique
	private static final int ENTITY_VERTEX_SIZE = 36;

	/** Writes vertices with exactly the bytes BufferBuilder's ENTITY fast path would produce. */
	@Unique
	private static void basalt$writeEntityVertices(long base, PoseStack.Pose pose, ModelPart.Polygon[] polygons, int[] map, float[] dst,
		int color, int overlayCoords, int lightCoords, Vector3f scratch) {
		int abgr = ARGB.toABGR(color);
		int rgba = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr);
		boolean le = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
		long p = base;
		int v = 0;
		for (ModelPart.Polygon polygon : polygons) {
			Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
			byte nx = basalt$normal(normal.x()), ny = basalt$normal(normal.y()), nz = basalt$normal(normal.z());
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				int u = map[v++] * 3;
				MemoryUtil.memPutFloat(p, dst[u]);
				MemoryUtil.memPutFloat(p + 4L, dst[u + 1]);
				MemoryUtil.memPutFloat(p + 8L, dst[u + 2]);
				MemoryUtil.memPutInt(p + 12L, rgba);
				MemoryUtil.memPutFloat(p + 16L, vertex.u());
				MemoryUtil.memPutFloat(p + 20L, vertex.v());
				basalt$putPackedUv(p + 24L, overlayCoords, le);
				basalt$putPackedUv(p + 28L, lightCoords, le);
				MemoryUtil.memPutByte(p + 32L, nx);
				MemoryUtil.memPutByte(p + 33L, ny);
				MemoryUtil.memPutByte(p + 34L, nz);
				p += ENTITY_VERTEX_SIZE;
			}
		}
	}

	@Unique
	private static void basalt$putPackedUv(long pointer, int packedUv, boolean le) {
		if (le) {
			MemoryUtil.memPutInt(pointer, packedUv);
		} else {
			MemoryUtil.memPutShort(pointer, (short) (packedUv & 65535));
			MemoryUtil.memPutShort(pointer + 2L, (short) (packedUv >> 16 & 65535));
		}
	}

	@Unique
	private static byte basalt$normal(float c) {
		return (byte) ((int) (Mth.clamp(c, -1.0F, 1.0F) * 127.0F) & 0xFF);
	}

	@Unique
	private void basalt$verify(long base, PoseStack.Pose pose, int[] map, float[] dst, int color, int overlayCoords, int lightCoords) {
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(map.length * ENTITY_VERTEX_SIZE)) {
			BufferBuilder ref = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
			Vector3f scratch = new Vector3f();
			int v = 0;
			for (ModelPart.Polygon polygon : this.polygons) {
				Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
				float nx = normal.x(), ny = normal.y(), nz = normal.z();
				for (ModelPart.Vertex vertex : polygon.vertices()) {
					int u = map[v++] * 3;
					ref.addVertex(dst[u], dst[u + 1], dst[u + 2], color, vertex.u(), vertex.v(), overlayCoords, lightCoords, nx, ny, nz);
				}
			}
			try (MeshData mesh = ref.buildOrThrow()) {
				java.nio.ByteBuffer expected = mesh.vertexBuffer();
				for (int i = 0; i < map.length; i++) {
					for (int b = 0; b < 35; b++) { // byte 35 is padding, never written by vanilla
						byte e = expected.get(i * ENTITY_VERTEX_SIZE + b), a = MemoryUtil.memGetByte(base + (long) i * ENTITY_VERTEX_SIZE + b);
						if (e != a) {
							throw new IllegalStateException("Basalt modelpart mismatch at vertex " + i + " byte " + b + ": " + e + " vs " + a);
						}
					}
				}
			}
		}
	}

	/**
	 * @author Basalt
	 * @reason Transform each distinct cube corner once instead of once per face.
	 */
	@Overwrite
	public void compile(final PoseStack.Pose pose, final VertexConsumer builder, final int lightCoords, final int overlayCoords, final int color) {
		CubeIndex index = this.basalt$index;
		if (index == null) {
			this.basalt$index = index = this.basalt$buildCubeIndex();
		}
		Matrix4f matrix = pose.pose();
		dev.basalt.client.modelpart.CubeScratch tmp = dev.basalt.client.modelpart.CubeScratch.get();
		Vector3f scratch = tmp.vec;
		float[] src = index.unique();
		float[] dst = tmp.floats(src.length);
		float m00 = matrix.m00(), m01 = matrix.m01(), m02 = matrix.m02();
		float m10 = matrix.m10(), m11 = matrix.m11(), m12 = matrix.m12();
		float m20 = matrix.m20(), m21 = matrix.m21(), m22 = matrix.m22();
		float m30 = matrix.m30(), m31 = matrix.m31(), m32 = matrix.m32();
		for (int i = 0; i < src.length; i += 3) {
			float x = src[i], y = src[i + 1], z = src[i + 2];
			// same fma order as Matrix4f.transformPosition
			dst[i] = Math.fma(m00, x, Math.fma(m10, y, Math.fma(m20, z, m30)));
			dst[i + 1] = Math.fma(m01, x, Math.fma(m11, y, Math.fma(m21, z, m31)));
			dst[i + 2] = Math.fma(m02, x, Math.fma(m12, y, Math.fma(m22, z, m32)));
		}

		int[] map = index.map();
		if (builder instanceof BulkVertexSink sink && builder.getClass() == BufferBuilder.class) {
			int total = map.length;
			long base = sink.basalt$reserveEntityVertices(total);
			if (base != -1L) {
				basalt$writeEntityVertices(base, pose, this.polygons, map, dst, color, overlayCoords, lightCoords, scratch);
				if (VERIFY) {
					basalt$verify(base, pose, map, dst, color, overlayCoords, lightCoords);
				}
				return;
			}
		}
		int v = 0;
		for (ModelPart.Polygon polygon : this.polygons) {
			Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
			float nx = normal.x();
			float ny = normal.y();
			float nz = normal.z();
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				int u = map[v++] * 3;
				builder.addVertex(dst[u], dst[u + 1], dst[u + 2], color, vertex.u(), vertex.v(), overlayCoords, lightCoords, nx, ny, nz);
			}
		}
	}
}
