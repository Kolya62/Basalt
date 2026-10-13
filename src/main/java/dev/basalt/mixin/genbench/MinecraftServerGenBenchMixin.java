package dev.basalt.mixin.genbench;

import dev.basalt.genbench.GenBench;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerGenBenchMixin {
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void basalt$genBench(BooleanSupplier haveTime, CallbackInfo ci) {
		GenBench.tick((MinecraftServer) (Object) this);
	}
}
