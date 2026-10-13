package dev.basalt.client.mixin.textcache;

import dev.basalt.client.textcache.TextCache;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Glyphs from a reloaded or closed font set are invalid: drop all cached layouts. */
@Mixin(FontSet.class)
public class FontSetMixin {
	@Inject(method = {"resetTextures", "close"}, at = @At("HEAD"))
	private void basalt$clear(CallbackInfo ci) {
		TextCache.clear();
	}
}
