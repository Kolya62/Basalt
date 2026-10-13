package dev.basalt.client.mixin.textcache;

import dev.basalt.client.textcache.TextCache;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void basalt$frame(CallbackInfo ci) {
		TextCache.endFrame();
	}
}
