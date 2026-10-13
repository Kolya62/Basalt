package dev.basalt.mixin.parallelgen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structure templates are shared by every chunk, and their per-block-type filter cache was a plain
 * HashMap filled lazily via computeIfAbsent. With FEATURES running in parallel two threads can
 * fill it at once (ConcurrentModificationException seen in testing). Use a ConcurrentHashMap;
 * the cached value is a pure function of the template, so results are unchanged.
 */
@Mixin(StructureTemplate.Palette.class)
public class StructureTemplatePaletteMixin {
	@Shadow @Final @Mutable private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void basalt$concurrentCache(CallbackInfo ci) {
		this.cache = new ConcurrentHashMap<>();
	}
}
