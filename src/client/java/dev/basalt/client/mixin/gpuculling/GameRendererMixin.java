package dev.basalt.client.mixin.gpuculling;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.basalt.client.gpuculling.GpuCulling;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	/** The final level projection (incl. view bobbing / nausea) is what terrain is drawn with. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
	private void basalt$captureProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		GpuCulling.PROJECTION.set(projectionMatrix);
	}
}
