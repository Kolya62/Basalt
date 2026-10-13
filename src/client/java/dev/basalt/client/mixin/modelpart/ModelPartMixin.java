package dev.basalt.client.mixin.modelpart;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.modelpart.CubeScratch;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every rotated model part allocated a Quaternionf per frame; it is consumed immediately by
 * PoseStack.rotate and never kept, so a per-thread scratch instance gives the same result.
 */
@Mixin(ModelPart.class)
public class ModelPartMixin {
	@WrapOperation(method = "translateAndRotate", at = @At(value = "NEW", target = "()Lorg/joml/Quaternionf;"))
	private Quaternionf basalt$scratchQuaternion(Operation<Quaternionf> original) {
		return CubeScratch.get().quat; // rotationZYX() overwrites every component
	}
}
