package dev.basalt.mixin.fastmap;

import dev.basalt.fastmap.StateTable;
import dev.basalt.fastmap.StateHolderMixinAccess;
import dev.basalt.fastmap.TableHolder;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * FerriteCore-style "fast map": multi-property states share one {@link StateTable} instead of each
 * holding a neighbour table (S[][]) and a value array. Single-property and singleton states, which
 * vanilla already shares, are left untouched (table == null).
 * <p>
 * Vanilla 26.x already shares neighbour rows between states, so the saving is the two per-state
 * arrays; the table is laid out so getValue/setValue stay a few shifts and array loads (no division,
 * no List calls), about as cheap as vanilla's own lookups.
 */
@Mixin(StateHolder.class)
public abstract class StateHolderMixin<O, S> implements TableHolder, StateHolderMixinAccess {
	@Shadow @Final private Property<?>[] propertyKeys;
	@Shadow @Final @Mutable private Comparable<?>[] propertyValues;
	@Shadow private S[][] neighbors;

	@Unique private StateTable basalt$table;
	@Unique private int basalt$index;

	@Shadow
	protected abstract int valueIndex(Property<?> property);

	@Override
	public boolean basalt$compact(StateTable table, int index) {
		// exhaustive check against the vanilla data before discarding it
		for (int p = 0; p < this.propertyKeys.length; p++) {
			if (table.keys[p] != this.propertyKeys[p] || table.value(index, p) != this.propertyValues[p]) {
				return false;
			}
			for (int v = 0; v < table.valueCount(p); v++) {
				if (table.with(index, p, v) != this.neighbors[p][v]) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void basalt$apply(StateTable table, int index) {
		this.basalt$table = table;
		this.basalt$index = index;
		this.neighbors = null;
		this.propertyValues = null;
	}

	/**
	 * @author Basalt
	 * @reason Values come from the shared table for compacted states.
	 */
	@Overwrite
	private <T extends Comparable<T>> @Nullable T getNullableValue(final Property<T> property) {
		int index = this.valueIndex(property);
		if (index == -1) {
			return null;
		}
		StateTable table = this.basalt$table;
		return property.getValueClass().cast(table != null ? table.value(this.basalt$index, index) : this.propertyValues[index]);
	}

	/**
	 * @author Basalt
	 * @reason Neighbour lookup by index arithmetic for compacted states.
	 */
	@Overwrite
	@SuppressWarnings("unchecked")
	private <T extends Comparable<T>, V extends T> S setValueInternal(final Property<T> property, final int propertyIndex, final V value) {
		int valueIndex = property.getInternalIndex((T) value);
		if (valueIndex < 0) {
			throw new IllegalArgumentException("Cannot set property " + property + " to " + value + " on " + this + ", it is not an allowed value");
		}
		StateTable table = this.basalt$table;
		return table != null ? (S) table.with(this.basalt$index, propertyIndex, valueIndex) : this.neighbors[propertyIndex][valueIndex];
	}

	/**
	 * @author Basalt
	 * @reason Same values, read through the shared table for compacted states.
	 */
	@Overwrite
	public Stream<Property.Value<?>> getValues() {
		int length = this.propertyKeys.length;
		return length == 0 ? Stream.empty() : IntStream.range(0, length).mapToObj(i -> basalt$createValue(this.propertyKeys[i],
			this.basalt$table != null ? this.basalt$table.value(this.basalt$index, i) : this.propertyValues[i]));
	}

	@Unique
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Property.Value<?> basalt$createValue(Property<?> key, Comparable<?> value) {
		return new Property.Value(key, value);
	}
}
