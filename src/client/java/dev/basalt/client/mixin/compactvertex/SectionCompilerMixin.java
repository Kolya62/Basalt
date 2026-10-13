package dev.basalt.client.mixin.compactvertex;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Meshes are still built on the CPU in vanilla's BLOCK format (repacked at upload), whatever the pipeline uses. */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	@WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;vertexFormat()Lcom/mojang/renderpearl/api/vertex/VertexFormat;"))
	private VertexFormat basalt$buildInBlockFormat(ChunkSectionLayer layer, Operation<VertexFormat> original) {
		return DefaultVertexFormat.BLOCK;
	}
}
