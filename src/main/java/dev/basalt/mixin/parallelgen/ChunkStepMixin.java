package dev.basalt.mixin.parallelgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.Basalt;
import dev.basalt.parallelgen.FeatureExecutor;
import dev.basalt.parallelgen.RegionLocks;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Vanilla runs the FEATURES step (trees, ores, decorations) synchronously on a single consecutive
 * "worldgen" executor, i.e. one chunk at a time server-wide, because a chunk's features may write
 * into its neighbours (blockStateWriteRadius = 1). Steps whose write areas don't overlap are safe
 * to run together, so this runs FEATURES on {@link FeatureExecutor} under an exclusive lock on the
 * chunk's (2r+1)^2 write area (one lock table per dimension). Other ordering guarantees come from
 * step dependencies and are untouched.
 */
@Mixin(ChunkStep.class)
public abstract class ChunkStepMixin {
	@Unique private static final ConcurrentHashMap<ResourceKey<Level>, RegionLocks> BASALT_LOCKS = new ConcurrentHashMap<>();

	@Shadow public abstract ChunkStatus targetStatus();

	@Shadow public abstract int blockStateWriteRadius();

	@WrapMethod(method = "apply")
	private CompletableFuture<ChunkAccess> basalt$parallelFeatures(
		WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original
	) {
		if (this.targetStatus() != ChunkStatus.FEATURES || !chunk.getPersistedStatus().isBefore(ChunkStatus.FEATURES)) {
			return original.call(context, cache, chunk);
		}
		ChunkPos pos = chunk.getPos();
		int r = Math.max(0, this.blockStateWriteRadius());
		long[] area = new long[(2 * r + 1) * (2 * r + 1)];
		int i = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				area[i++] = ChunkPos.pack(pos.x() + dx, pos.z() + dz);
			}
		}
		// same priority vanilla's worldgen dispatcher would use (lower queue level = closer to a player)
		int priority = cache.get(pos.x(), pos.z()).getQueueLevel();
		RegionLocks locks = BASALT_LOCKS.computeIfAbsent(context.level().dimension(), k -> new RegionLocks());
		CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
		locks.acquire(area).thenRun(() -> {
			try {
				FeatureExecutor.execute(priority, () -> {
					try {
						original.call(context, cache, chunk).whenComplete((generated, failure) -> {
							locks.release(area);
							if (failure != null) {
								result.completeExceptionally(failure);
							} else {
								result.complete(generated);
							}
						});
					} catch (Throwable t) {
						basalt$fail(locks, area, result, pos, t);
					}
				});
			} catch (Throwable t) {
				basalt$fail(locks, area, result, pos, t);
			}
		});
		return result;
	}

	@Unique
	private static void basalt$fail(RegionLocks locks, long[] area, CompletableFuture<ChunkAccess> result, ChunkPos pos, Throwable t) {
		locks.release(area);
		Basalt.LOGGER.error("Parallel FEATURES step failed for chunk {}", pos, t);
		result.completeExceptionally(t);
	}
}
