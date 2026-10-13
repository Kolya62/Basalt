package dev.basalt.mixin.poi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.poi.ColumnIndex;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Optional;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tracks, per chunk column, whether SectionStorage has unpacked it and whether any section in it
 * exists. Vanilla only ever adds or overwrites entries in its storage map (never removes), and all
 * writes go through the two methods below, so these sets stay exact (or conservatively "non-empty").
 */
@Mixin(SectionStorage.class)
public class SectionStorageMixin implements ColumnIndex {
	@Unique private final LongOpenHashSet basalt$unpacked = new LongOpenHashSet();
	@Unique private final LongOpenHashSet basalt$hasSections = new LongOpenHashSet();

	@Unique
	private void basalt$track(long sectionKey, Object value, boolean unpacking) {
		long chunkKey = SectionPos.of(sectionKey).chunk().pack();
		if (unpacking) {
			this.basalt$unpacked.add(chunkKey);
		}
		if (value instanceof Optional<?> optional && optional.isPresent()) {
			this.basalt$hasSections.add(chunkKey);
		}
	}

	@WrapOperation(method = "getOrCreate", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
	private Object basalt$trackCreate(Long2ObjectMap<Object> map, long key, Object value, Operation<Object> original) {
		this.basalt$track(key, value, false);
		return original.call(map, key, value);
	}

	@WrapOperation(
		method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V",
		at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;")
	)
	private Object basalt$trackUnpack(Long2ObjectMap<Object> map, long key, Object value, Operation<Object> original) {
		this.basalt$track(key, value, true);
		return original.call(map, key, value);
	}

	@Override
	public boolean basalt$isColumnKnownEmpty(long chunkKey) {
		return this.basalt$unpacked.contains(chunkKey) && !this.basalt$hasSections.contains(chunkKey);
	}
}
