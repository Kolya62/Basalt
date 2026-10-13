package dev.basalt.client.mixin.bench;

import dev.basalt.client.bench.Zones;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class LevelRendererBenchMixin {
	@Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"), require = 0)
	private void basalt$zoneBegin(CallbackInfoReturnable<?> cir) {
		Zones.begin(Zones.CHUNK_PREPARE);
	}

	@Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"), require = 0)
	private void basalt$zoneEnd(CallbackInfoReturnable<?> cir) {
		Zones.end(Zones.CHUNK_PREPARE);
	}
}
