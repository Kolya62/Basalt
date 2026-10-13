package dev.basalt.client.mixin.gpuculling;

import dev.basalt.client.gpuculling.CullState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin implements CullState {
	@Unique private boolean basalt$occluded;

	@Override public boolean basalt$occluded() { return this.basalt$occluded; }
	@Override public void basalt$setOccluded(boolean occluded) { this.basalt$occluded = occluded; }
}
