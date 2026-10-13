package dev.basalt.client.mixin.gpuculling;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkSectionsToRender.DrawIndirect.class)
public interface DrawIndirectAccess {
	@Accessor("drawGroupsPerLayer")
	EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> basalt$groups();

	@Accessor("chunkSectionInfos")
	GpuBufferSlice basalt$sectionInfos();
}
