package dev.basalt.client.mixin.gpuculling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.basalt.client.gpuculling.GpuCulling;
import dev.basalt.client.gpuculling.SplittablePass;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@org.spongepowered.asm.mixin.Shadow private com.mojang.renderpearl.api.textures.GpuSampler chunkLayerSampler;
	@org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.client.renderer.texture.TextureManager textureManager;

	/** The main scene pass ("Main", or "Solid" with improved transparency) becomes splittable. */
	@WrapOperation(
		method = "*",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;")
	)
	private RenderPass basalt$splittableMainPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor,
		GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass pass = original.call(encoder, label, color, clearColor, depth, clearDepth);
		String name = label.get();
		if (!GpuCulling.wantsSplit() || depth == null || !(name.equals("Main") || name.equals("Solid"))) {
			return pass;
		}
		return SplittablePass.wrap(pass, () -> {
			RenderPass next = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(label, color, Optional.empty(), depth, OptionalDouble.empty());
			RenderSystem.bindDefaultUniforms(next);
			return next;
		}, depth);
	}

	@Inject(method = "executeSolid", at = @At("RETURN"))
	private void basalt$afterOpaque(ChunkSectionsToRender chunks, net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, CallbackInfo ci) {
		SplittablePass splittable = SplittablePass.of(pass);
		if (splittable != null) {
			GpuCulling.afterOpaque(splittable, this.chunkLayerSampler,
				this.textureManager.getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTextureView());
		}
	}
}
