package dev.basalt.client.compactvertex;

import org.jspecify.annotations.Nullable;

/** Mixed into CompiledSectionMesh: per-layer quad facing groups of the uploaded geometry. */
public interface FacingHolder {
	@Nullable FacingInfo basalt$facing(int layerOrdinal);

	void basalt$setFacing(int layerOrdinal, @Nullable FacingInfo info);
}
