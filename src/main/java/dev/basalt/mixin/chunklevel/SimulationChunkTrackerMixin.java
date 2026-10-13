package dev.basalt.mixin.chunklevel;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Is this chunk ticking?" is asked by every block entity and entity every tick, each time a
 * hash lookup. Consecutive queries are often for the same chunk, so remember the last answer;
 * any level change clears it.
 * <p>
 * The entry is one packed long (valid bit, 22-bit chunk x and z, 8-bit level): a miss costs one
 * plain store, no allocation and no fence (ticket propagation also calls getLevel, for ever-changing
 * neighbours, so misses are common), and a racing reader can never pair one chunk's key with another
 * chunk's level. Chunks beyond the 22-bit range (far outside the world border) are not cached.
 */
@Mixin(SimulationChunkTracker.class)
public class SimulationChunkTrackerMixin {
	@Unique private static final long BASALT_VALID = 1L << 63;
	@Unique private static final long BASALT_KEY_MASK = BASALT_VALID | (1L << 44) - 1;

	@Shadow @Final protected Long2ByteMap chunks;

	@Unique private long basalt$last;

	/**
	 * @author Basalt
	 * @reason One-entry cache in front of the hash map; invalidated in setLevel.
	 */
	@Overwrite
	protected int getLevel(final long node) {
		int x = ChunkPos.getX(node), z = ChunkPos.getZ(node);
		if ((x + (1 << 21) | z + (1 << 21)) >>> 22 != 0) {
			return this.chunks.get(node);
		}
		long tag = BASALT_VALID | x & 0x3FFFFFL | (z & 0x3FFFFFL) << 22;
		long last = this.basalt$last;
		if ((last & BASALT_KEY_MASK) == tag) {
			return (int) (last >>> 44 & 0xFF);
		}
		int level = this.chunks.get(node);
		this.basalt$last = tag | (long) (level & 0xFF) << 44;
		return level;
	}

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void basalt$invalidate(long node, int level, CallbackInfo ci) {
		this.basalt$last = 0L;
	}
}
