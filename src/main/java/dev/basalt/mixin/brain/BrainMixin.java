package dev.basalt.mixin.brain;

import dev.basalt.brain.BehaviorIndex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every mob with a Brain walks a TreeMap -> HashMap -> LinkedHashSet twice per tick and allocates
 * a list of running behaviours. Iterate a flattened array instead (same order), rebuilt only when
 * behaviours are added or removed. Active-activity checks stay live, exactly as in vanilla, since
 * a behaviour starting can switch the brain's activity mid-loop.
 */
@Mixin(Brain.class)
public class BrainMixin<E extends LivingEntity> {
	@Shadow @Final private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;
	@Shadow @Final private Set<Activity> activeActivities;

	@Unique private BehaviorIndex<E> basalt$index;

	@Unique
	private BehaviorIndex<E> basalt$index() {
		BehaviorIndex<E> index = this.basalt$index;
		if (index == null) {
			this.basalt$index = index = new BehaviorIndex<>(this.availableBehaviorsByPriority);
		}
		return index;
	}

	@Inject(method = {"addActivity", "removeAllBehaviors"}, at = @At("TAIL"))
	private void basalt$invalidate(CallbackInfo ci) {
		this.basalt$index = null;
	}

	/**
	 * @author Basalt
	 * @reason Flattened iteration; same order and same live activity checks as vanilla.
	 */
	@Overwrite
	private void startEachNonRunningBehavior(final ServerLevel level, final E body) {
		long time = level.getGameTime();
		BehaviorIndex<E> index = this.basalt$index();
		Activity[] activities = index.groupActivity;
		int[] starts = index.groupStart;
		BehaviorControl<? super E>[] behaviors = index.behaviors;
		for (int g = 0; g < activities.length; g++) {
			if (this.activeActivities.contains(activities[g])) {
				for (int i = starts[g], end = starts[g + 1]; i < end; i++) {
					BehaviorControl<? super E> behavior = behaviors[i];
					if (behavior.getStatus() == Behavior.Status.STOPPED) {
						behavior.tryStart(level, body, time);
					}
				}
			}
		}
	}

	/**
	 * @author Basalt
	 * @reason Snapshot running behaviours into a reusable array instead of a new list per tick.
	 */
	@Overwrite
	private void tickEachRunningBehavior(final ServerLevel level, final E body) {
		long timestamp = level.getGameTime();
		BehaviorIndex<E> index = this.basalt$index();
		BehaviorControl<? super E>[] behaviors = index.behaviors;
		BehaviorControl<? super E>[] running = index.running;
		int n = 0;
		for (BehaviorControl<? super E> behavior : behaviors) {
			if (behavior.getStatus() == Behavior.Status.RUNNING) {
				running[n++] = behavior;
			}
		}
		for (int i = 0; i < n; i++) {
			BehaviorControl<? super E> behavior = running[i];
			running[i] = null;
			behavior.tickOrStop(level, body, timestamp);
		}
	}

	/**
	 * @author Basalt
	 * @reason Same result as vanilla, built from the flattened index.
	 */
	@Overwrite
	public List<BehaviorControl<? super E>> getRunningBehaviors() {
		List<BehaviorControl<? super E>> result = new ObjectArrayList<>();
		for (BehaviorControl<? super E> behavior : this.basalt$index().behaviors) {
			if (behavior.getStatus() == Behavior.Status.RUNNING) {
				result.add(behavior);
			}
		}
		return result;
	}
}
