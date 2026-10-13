package dev.basalt.client.mixin.textcache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.textcache.TextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TextFeatureRenderer.class)
public class TextFeatureRendererMixin {
	@WrapOperation(method = "renderText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"))
	private static Font.PreparedText basalt$cached(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int background,
		Operation<Font.PreparedText> original) {
		return TextCache.get(font, text, x, y, color, shadow, includeEmpty, background,
			() -> original.call(font, text, x, y, color, shadow, includeEmpty, background));
	}
}
