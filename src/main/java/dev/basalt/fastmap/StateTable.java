package dev.basalt.fastmap;

import java.util.List;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * One per multi-property StateDefinition: its states in cartesian-product order (last property
 * fastest), so a state's value for property p is (index / stride[p]) % size[p] and changing it is
 * index arithmetic. Replaces each state's private neighbour table and value array.
 */
public final class StateTable {
	public final Object[] states;
	public final Property<?>[] keys;
	public final List<?>[] values;
	public final int[] stride;

	public StateTable(Object[] states, Property<?>[] keys) {
		this.states = states;
		this.keys = keys;
		this.values = new List<?>[keys.length];
		this.stride = new int[keys.length];
		int s = 1;
		for (int p = keys.length - 1; p >= 0; p--) {
			this.values[p] = keys[p].getPossibleValues();
			this.stride[p] = s;
			s *= this.values[p].size();
		}
	}

	public int valueIndex(int stateIndex, int property) {
		return stateIndex / this.stride[property] % this.values[property].size();
	}

	public Comparable<?> value(int stateIndex, int property) {
		return (Comparable<?>) this.values[property].get(this.valueIndex(stateIndex, property));
	}

	public Object with(int stateIndex, int property, int newValueIndex) {
		return this.states[stateIndex + (newValueIndex - this.valueIndex(stateIndex, property)) * this.stride[property]];
	}
}
