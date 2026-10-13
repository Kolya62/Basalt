package dev.basalt.client.mixin.entityculling;

import dev.basalt.client.culling.OcclusionCuller;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the occlusion culler when its cached answers may have become wrong. */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void basalt$blockChanged(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		if (old.isSolidRender() != current.isSolidRender()) {
			OcclusionCuller.worldChanged();
		}
	}

	@Inject(method = "onChunkLoaded", at = @At("HEAD"))
	private void basalt$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
		OcclusionCuller.worldChanged();
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void basalt$chunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
		OcclusionCuller.worldChanged();
	}
}
