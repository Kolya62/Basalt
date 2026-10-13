package dev.basalt.client.mixin.entityculling;

import dev.basalt.client.culling.Cullable;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public class EntityMixin implements Cullable {
	@Unique private volatile boolean basalt$culled;
	@Unique private volatile long basalt$lastRequested;

	@Override public boolean basalt$isCulled() { return this.basalt$culled; }
	@Override public void basalt$setCulled(boolean culled) { this.basalt$culled = culled; }
	@Override public long basalt$lastRequested() { return this.basalt$lastRequested; }
	@Override public void basalt$setLastRequested(long ms) { this.basalt$lastRequested = ms; }
}
