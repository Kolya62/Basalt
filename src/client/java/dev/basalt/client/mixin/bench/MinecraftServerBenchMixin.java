package dev.basalt.client.mixin.bench;

import dev.basalt.client.bench.Zones;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerBenchMixin {
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void basalt$zoneBegin(BooleanSupplier haveTime, CallbackInfo ci) {
		Zones.begin(Zones.SERVER_TICK);
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void basalt$zoneEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		Zones.end(Zones.SERVER_TICK);
	}
}
