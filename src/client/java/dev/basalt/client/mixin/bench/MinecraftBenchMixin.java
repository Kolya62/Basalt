package dev.basalt.client.mixin.bench;

import dev.basalt.client.bench.Bench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftBenchMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void basalt$benchFrame(boolean advanceGameTime, CallbackInfo ci) {
		Bench.onFrame((Minecraft) (Object) this);
		dev.basalt.client.bench.GpuTiming.frameStart();
	}

	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void basalt$benchFrameEnd(boolean advanceGameTime, CallbackInfo ci) {
		dev.basalt.client.bench.GpuTiming.frameEnd();
	}
}
