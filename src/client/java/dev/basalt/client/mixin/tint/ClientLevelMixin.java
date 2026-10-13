package dev.basalt.client.mixin.tint;

import dev.basalt.client.tint.TintBlender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Inject(method = "calculateBlockTint", at = @At("HEAD"), cancellable = true)
	private void basalt$blend(BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
		int dist = Minecraft.getInstance().options.biomeBlendRadius().get();
		if (dist > 0) {
			cir.setReturnValue(TintBlender.blend((ClientLevel) (Object) this, pos, resolver, dist));
		}
	}
}
