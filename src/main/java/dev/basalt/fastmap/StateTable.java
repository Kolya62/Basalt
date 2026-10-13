package dev.basalt.fastmap;

import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * One per multi-property StateDefinition, replacing each state's private neighbour table and value
 * array. A state is identified by a packed index: every property gets its own bit field (width =
 * bits needed for its value count), so reading or changing one property is a shift and a mask,
 * never a division, and values come from plain arrays (no List calls on a megamorphic site).
 * Unused bit patterns map to null in {@link #states}.
 */
public final class StateTable {
	/** Definitions needing more bits stay vanilla: the sparse state array would get too large. */
	public static final int MAX_BITS = 16;

	public final Object[] states;
	public final Property<?>[] keys;
	public final Comparable<?>[][] values;
	private final int[] shift;
	private final int[] mask;
	/** Packed index of the i-th state in vanilla's cartesian-product order (last property fastest). */
	private final int[] packedByOrder;

	private StateTable(Object[] states, Property<?>[] keys, Comparable<?>[][] values, int[] shift, int[] mask, int[] packedByOrder) {
		this.states = states;
		this.keys = keys;
		this.values = values;
		this.shift = shift;
		this.mask = mask;
		this.packedByOrder = packedByOrder;
	}

	/** Builds the table for states in vanilla's cartesian-product order, or null if it would need more than MAX_BITS. */
	public static @Nullable StateTable create(Object[] statesInOrder, Property<?>[] keys) {
		int n = keys.length;
		Comparable<?>[][] values = new Comparable<?>[n][];
		int[] shift = new int[n];
		int[] mask = new int[n];
		int bits = 0;
		for (int p = n - 1; p >= 0; p--) {
			values[p] = keys[p].getPossibleValues().toArray(new Comparable<?>[0]);
			int width = 32 - Integer.numberOfLeadingZeros(Math.max(0, values[p].length - 1));
			shift[p] = bits;
			mask[p] = (1 << width) - 1;
			bits += width;
			if (bits > MAX_BITS) {
				return null;
			}
		}
		Object[] states = new Object[1 << bits];
		int[] packedByOrder = new int[statesInOrder.length];
		for (int i = 0; i < statesInOrder.length; i++) {
			// decompose the cartesian index (last property fastest) into value indices
			int rest = i, packed = 0;
			for (int p = n - 1; p >= 0; p--) {
				int size = values[p].length;
				packed |= (rest % size) << shift[p];
				rest /= size;
			}
			packedByOrder[i] = packed;
			states[packed] = statesInOrder[i];
		}
		return new StateTable(states, keys, values, shift, mask, packedByOrder);
	}

	public int packedIndex(int orderIndex) {
		return this.packedByOrder[orderIndex];
	}

	public int valueIndex(int index, int property) {
		return index >>> this.shift[property] & this.mask[property];
	}

	public Comparable<?> value(int index, int property) {
		return this.values[property][index >>> this.shift[property] & this.mask[property]];
	}

	public Object with(int index, int property, int newValueIndex) {
		int s = this.shift[property];
		return this.states[index & ~(this.mask[property] << s) | newValueIndex << s];
	}

	public int valueCount(int property) {
		return this.values[property].length;
	}
}
