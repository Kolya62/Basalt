package dev.basalt.mixin.randomtick;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Random ticks check every picked block's fluid for random ticking, although the section already
 * counts its randomly-ticking fluids. With a zero count the answer is always false, so skip the
 * lookup. The RNG is consumed exactly as before, so world behaviour is unchanged.
 */
@Mixin(ServerLevel.class)
public class ServerLevelMixin {
	@WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isRandomlyTicking()Z"))
	private boolean basalt$skipFluidCheck(FluidState fluidState, Operation<Boolean> original, @Local LevelChunkSection section) {
		return section.isRandomlyTickingFluids() && original.call(fluidState);
	}
}
