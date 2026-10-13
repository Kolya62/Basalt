package dev.basalt.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Brain's behaviours flattened in exactly the order vanilla iterates its
 * TreeMap(priority) -> HashMap(activity) -> LinkedHashSet(behaviour) structure.
 * groupActivity[g] owns behaviours[groupStart[g] .. groupStart[g + 1]).
 */
public final class BehaviorIndex<E extends LivingEntity> {
	public final Activity[] groupActivity;
	public final int[] groupStart;
	public final BehaviorControl<? super E>[] behaviors;
	/** Reusable snapshot buffer for "currently running" behaviours. */
	public final BehaviorControl<? super E>[] running;

	@SuppressWarnings("unchecked")
	public BehaviorIndex(Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> byPriority) {
		List<Activity> activities = new ArrayList<>();
		List<Integer> starts = new ArrayList<>();
		List<BehaviorControl<? super E>> all = new ArrayList<>();
		for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity : byPriority.values()) {
			for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : byActivity.entrySet()) {
				activities.add(entry.getKey());
				starts.add(all.size());
				all.addAll(entry.getValue());
			}
		}
		starts.add(all.size());
		this.groupActivity = activities.toArray(new Activity[0]);
		this.groupStart = starts.stream().mapToInt(Integer::intValue).toArray();
		this.behaviors = all.toArray(new BehaviorControl[0]);
		this.running = new BehaviorControl[this.behaviors.length];
	}
}
