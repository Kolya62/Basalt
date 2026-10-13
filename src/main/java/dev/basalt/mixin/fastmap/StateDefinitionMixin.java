package dev.basalt.mixin.fastmap;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.basalt.Basalt;
import dev.basalt.fastmap.StateTable;
import dev.basalt.fastmap.StateHolderMixinAccess;
import dev.basalt.fastmap.TableHolder;
import java.util.Map;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(StateDefinition.class)
public class StateDefinitionMixin {
	@Unique private static int basalt$compacted, basalt$skipped;

	@ModifyReturnValue(method = "createMultiPropertyStates", at = @At("RETURN"))
	private static <O, S extends StateHolder<O, S>> ImmutableList<S> basalt$compact(ImmutableList<S> states, O owner,
		StateDefinition.Factory<O, S> factory, Map<String, Property<?>> propertiesByName) {
		Property<?>[] keys = propertiesByName.values().toArray(new Property<?>[0]);
		StateTable table = StateTable.create(states.toArray(), keys);
		if (table == null) {
			basalt$skipped++; // too many property bits for a packed table; vanilla's shared rows are fine
			return states;
		}
		for (int i = 0; i < states.size(); i++) {
			if (!((TableHolder) states.get(i)).basalt$compact(table, table.packedIndex(i))) {
				basalt$skipped++;
				Basalt.LOGGER.warn("[fastmap] state table mismatch for {}, left as vanilla", owner);
				return states;
			}
		}
		for (int i = 0; i < states.size(); i++) {
			((StateHolderMixinAccess) states.get(i)).basalt$apply(table, table.packedIndex(i));
		}
		basalt$compacted++;
		return states;
	}
}
