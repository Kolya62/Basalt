package dev.basalt.client.mixin.entityculling;

import dev.basalt.client.culling.Cullable;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockEntity.class)
public class BlockEntityMixin implements Cullable {
	@Unique private volatile boolean basalt$culled;
	@Unique private volatile long basalt$lastRequested;
	@Unique private long basalt$tracedKey, basalt$tracedAt; // culling thread only

	@Override public boolean basalt$isCulled() { return this.basalt$culled; }
	@Override public void basalt$setCulled(boolean culled) { this.basalt$culled = culled; }
	@Override public long basalt$lastRequested() { return this.basalt$lastRequested; }
	@Override public void basalt$setLastRequested(long ms) { this.basalt$lastRequested = ms; }
	@Override public long basalt$tracedKey() { return this.basalt$tracedKey; }
	@Override public long basalt$tracedAt() { return this.basalt$tracedAt; }
	@Override public void basalt$setTraced(long key, long ms) { this.basalt$tracedKey = key; this.basalt$tracedAt = ms; }
}
