package dev.basalt.mixin.parallelgen;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A structure spanning several chunks is placed piece by piece from each chunk's FEATURES step, and
 * pieces keep check-then-set flags that assume those calls never overlap (e.g. a mineshaft spider
 * corridor's hasPlacedSpider, a desert pyramid's hasPlacedChest): two chunks far enough apart to
 * run in parallel could both see "not placed yet" and place two spawners. Placing one structure
 * start is therefore serialised; different structures still place in parallel. No lock is taken
 * inside placement, so this cannot deadlock.
 */
@Mixin(StructureStart.class)
public class StructureStartMixin {
	@WrapMethod(method = "placeInChunk")
	private void basalt$oneChunkAtATime(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
		BoundingBox chunkBB, ChunkPos chunkPos, Operation<Void> original) {
		synchronized (this) {
			original.call(level, structureManager, generator, random, chunkBB, chunkPos);
		}
	}
}
