package dev.basalt.client.mixin.bench;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.basalt.client.bench.GpuTiming;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderBenchMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void basalt$gpuStart(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
		GpuTiming.terrain(pass, true);
	}

	@Inject(method = "renderGroup", at = @At("TAIL"))
	private void basalt$gpuEnd(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
		GpuTiming.terrain(pass, false);
	}
}
