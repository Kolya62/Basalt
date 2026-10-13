package dev.basalt.client.mixin.entityculling;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.culling.OcclusionCuller;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow private @Nullable ClientLevel level;

	@Inject(method = "extract", at = @At("HEAD"))
	private void basalt$updateCullingCamera(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		Vec3 pos = camera.position();
		OcclusionCuller.updateCamera(this.level, pos.x(), pos.y(), pos.z());
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean basalt$cullEntity(boolean visible, Entity entity) {
		return visible && !OcclusionCuller.isCulled(entity);
	}

	@WrapOperation(
		method = "extractVisibleBlockEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;", ordinal = 0)
	)
	private BlockEntityRenderState basalt$cullBlockEntity(
		BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean global,
		Operation<BlockEntityRenderState> original
	) {
		if (breakProgress == null && OcclusionCuller.isCulled(blockEntity)) {
			return null;
		}
		return original.call(dispatcher, blockEntity, partialTicks, breakProgress, global);
	}
}
