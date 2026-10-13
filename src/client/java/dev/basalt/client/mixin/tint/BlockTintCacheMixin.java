package dev.basalt.client.mixin.tint;

import dev.basalt.client.tint.TintBlender;
import net.minecraft.client.color.block.BlockTintCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Whatever makes vanilla drop cached tints (chunk (re)loads, biome blend change) also drops ours. */
@Mixin(BlockTintCache.class)
public class BlockTintCacheMixin {
	@Inject(method = {"invalidateForChunk", "invalidateAll"}, at = @At("HEAD"))
	private void basalt$invalidate(CallbackInfo ci) {
		TintBlender.EPOCH.incrementAndGet();
	}
}
