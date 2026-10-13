package dev.basalt.client.mixin.gpuculling;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderAccess {
	@Accessor("terrainTransformUBO")
	GpuBufferSlice basalt$terrainTransform();

	@Accessor("maxIndicesRequired")
	int basalt$maxIndices();
}
