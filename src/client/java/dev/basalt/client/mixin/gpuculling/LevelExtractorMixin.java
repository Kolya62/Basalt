package dev.basalt.client.mixin.gpuculling;

import dev.basalt.client.gpuculling.GpuCulling;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Joining, switching or leaving a level drops GPU culling's references to the old sections. */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void basalt$resetCulling(@Nullable ClientLevel level, CallbackInfo ci) {
		GpuCulling.reset();
	}
}
