package dev.basalt.client.mixin.bench;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.client.bench.Bench;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;

/** Bench: wall time of every section mesh build (all worker threads). */
@Mixin(SectionCompiler.class)
public class SectionCompilerBenchMixin {
	@WrapMethod(method = "compile")
	private SectionCompiler.Results basalt$time(
		net.minecraft.core.SectionPos pos, net.minecraft.client.renderer.chunk.RenderSectionRegion region,
		com.mojang.blaze3d.vertex.VertexSorting sorting, net.minecraft.client.renderer.SectionBufferBuilderPack builders,
		Operation<SectionCompiler.Results> original
	) {
		long t = System.nanoTime();
		try {
			return original.call(pos, region, sorting, builders);
		} finally {
			Bench.MESH_NS.addAndGet(System.nanoTime() - t);
			Bench.MESH_COUNT.incrementAndGet();
		}
	}
}
