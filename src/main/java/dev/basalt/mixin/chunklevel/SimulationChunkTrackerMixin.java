package dev.basalt.mixin.chunklevel;

import dev.basalt.chunklevel.LevelEntry;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
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
 * hash lookup. Consecutive queries are usually for the same chunk, so remember the last answer;
 * any level change clears it.
 */
@Mixin(SimulationChunkTracker.class)
public class SimulationChunkTrackerMixin {
	@Shadow @Final protected Long2ByteMap chunks;

	@Unique private volatile LevelEntry basalt$last;

	/**
	 * @author Basalt
	 * @reason One-entry cache in front of the hash map; invalidated in setLevel.
	 */
	@Overwrite
	protected int getLevel(final long node) {
		LevelEntry last = this.basalt$last;
		if (last != null && last.key() == node) {
			return last.level();
		}
		int level = this.chunks.get(node);
		this.basalt$last = new LevelEntry(node, level);
		return level;
	}

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void basalt$invalidate(long node, int level, CallbackInfo ci) {
		this.basalt$last = null;
	}
}
