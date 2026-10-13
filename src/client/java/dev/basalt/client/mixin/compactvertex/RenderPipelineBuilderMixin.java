package dev.basalt.client.mixin.compactvertex;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.basalt.client.compactvertex.CompactTerrain;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every chunk pipeline (vertex shader core/terrain, incl. multidraw, wireframe, OIT) gets the compact format + shader. */
@Mixin(RenderPipeline.Builder.class)
public class RenderPipelineBuilderMixin {
	@Shadow @Final private Map<ShaderType, Identifier> shaders;
	@Shadow @Final private VertexFormat[] vertexFormatPerBuffer;

	@Inject(method = "build", at = @At("HEAD"))
	private void basalt$compactTerrain(CallbackInfoReturnable<RenderPipeline> cir) {
		if (CompactTerrain.VANILLA_SHADER.equals(this.shaders.get(ShaderType.VERTEX))) {
			this.shaders.put(ShaderType.VERTEX, CompactTerrain.SHADER);
			this.vertexFormatPerBuffer[0] = CompactTerrain.FORMAT;
		}
	}
}
