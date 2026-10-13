package dev.basalt.client.mixin.modelpart;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.basalt.client.modelpart.CubeScratch;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Item and block-model quads (dropped items, held items, item frames) allocated five Vector3f per
 * quad. Same arithmetic as vanilla, with the normal in per-thread scratch and positions inlined.
 */
@Mixin(VertexConsumer.class)
public interface VertexConsumerMixin {
	/**
	 * @author Basalt
	 * @reason allocation-free putBakedQuad
	 */
	@Overwrite
	default void putBakedQuad(final PoseStack.Pose pose, final BakedQuad quad, final QuadInstance instance) {
		VertexConsumer self = (VertexConsumer) this;
		Matrix4f m = pose.pose();
		Vector3f normal = pose.transformNormal(quad.direction().getUnitVec3f(), CubeScratch.get().vec);
		float nx = normal.x(), ny = normal.y(), nz = normal.z();
		int lightEmission = quad.materialInfo().lightEmission();
		for (int vertex = 0; vertex < 4; vertex++) {
			Vector3fc p = quad.position(vertex);
			float x = p.x(), y = p.y(), z = p.z();
			long packedUv = quad.packedUV(vertex);
			self.addVertex(
				Math.fma(m.m00(), x, Math.fma(m.m10(), y, Math.fma(m.m20(), z, m.m30()))),
				Math.fma(m.m01(), x, Math.fma(m.m11(), y, Math.fma(m.m21(), z, m.m31()))),
				Math.fma(m.m02(), x, Math.fma(m.m12(), y, Math.fma(m.m22(), z, m.m32()))),
				instance.getColor(vertex), UVPair.unpackU(packedUv), UVPair.unpackV(packedUv),
				instance.overlayCoords(), instance.getLightCoordsWithEmission(vertex, lightEmission), nx, ny, nz
			);
		}
	}
}
