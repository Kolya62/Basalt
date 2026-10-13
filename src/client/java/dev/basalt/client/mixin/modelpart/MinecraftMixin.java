package dev.basalt.client.mixin.modelpart;

import dev.basalt.client.modelpart.VertexInterner;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Models are baked during resource reloads; once one finishes the interning maps are dropped. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
	private void basalt$dropVertexInterning(CallbackInfo ci) {
		VertexInterner.clear();
	}
}
