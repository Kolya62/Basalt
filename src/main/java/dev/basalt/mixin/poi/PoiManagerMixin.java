package dev.basalt.mixin.poi;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.basalt.poi.ColumnIndex;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;

/** Villager/bee/portal POI searches scan ~24 sections per chunk; skip columns known to have none. */
@Mixin(PoiManager.class)
public class PoiManagerMixin {
	/** -Dbasalt.poi.verify=true runs vanilla too and checks it really finds nothing. */
	private static final boolean VERIFY = Boolean.getBoolean("basalt.poi.verify");

	@WrapMethod(method = "getInChunk")
	private Stream<PoiRecord> basalt$skipEmptyColumns(
		Predicate<Holder<PoiType>> predicate, ChunkPos chunkPos, PoiManager.Occupancy occupancy, Operation<Stream<PoiRecord>> original
	) {
		if (((ColumnIndex) this).basalt$isColumnKnownEmpty(chunkPos.pack())) {
			if (VERIFY && original.call(predicate, chunkPos, occupancy).findAny().isPresent()) {
				throw new IllegalStateException("Basalt poi mismatch: column " + chunkPos + " has POIs but was marked empty");
			}
			return Stream.empty();
		}
		return original.call(predicate, chunkPos, occupancy);
	}
}
