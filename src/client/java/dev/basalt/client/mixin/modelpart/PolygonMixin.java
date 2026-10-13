package dev.basalt.client.mixin.modelpart;

import dev.basalt.client.modelpart.VertexInterner;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity model vertices are immutable 5-float records and many models are identical (armour,
 * baby/adult and variant layers), so equal vertices - and equal polygon vertex arrays - are shared.
 * Record equality on floats is bitwise (Float.compare), so shared values are exactly identical.
 */
@Mixin(ModelPart.Polygon.class)
public class PolygonMixin {
	@Shadow @Final @Mutable private ModelPart.Vertex[] vertices;

	@Inject(method = "<init>([Lnet/minecraft/client/model/geom/ModelPart$Vertex;FFFFFFZLnet/minecraft/core/Direction;)V", at = @At("RETURN"))
	private void basalt$share(ModelPart.Vertex[] vertices, float u0, float v0, float u1, float v1, float xTexSize, float yTexSize, boolean mirror,
		Direction facing, CallbackInfo ci) {
		this.vertices = VertexInterner.intern(this.vertices);
	}
}
