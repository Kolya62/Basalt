package dev.basalt.client.mixin.compactvertex;

import dev.basalt.client.compactvertex.FacingHolder;
import dev.basalt.client.compactvertex.FacingInfo;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
public class CompiledSectionMeshMixin implements FacingHolder {
	@Unique private final FacingInfo[] basalt$facing = new FacingInfo[4];

	@Override
	public FacingInfo basalt$facing(int layerOrdinal) {
		return this.basalt$facing[layerOrdinal];
	}

	@Override
	public void basalt$setFacing(int layerOrdinal, FacingInfo info) {
		this.basalt$facing[layerOrdinal] = info;
	}
}
