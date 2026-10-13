package dev.basalt.fastmap;

import dev.basalt.fastmap.StateTable;

/** The apply half of the compaction, exposed to StateDefinitionMixin (implemented by StateHolderMixin). */
public interface StateHolderMixinAccess {
	void basalt$apply(StateTable table, int index);
}
