package dev.basalt.mixin.entitysections;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Vanilla answers "entity sections touching this box" by walking, for each section X in range,
 * every existing section with that X anywhere in the world (a whole Y/Z strip) and filtering.
 * Entity pushing asks this for every mob every tick with a tiny box. For small boxes, look the
 * few candidate keys up directly instead, visiting them in exactly vanilla's (sorted-key) order.
 */
@Mixin(EntitySectionStorage.class)
public class EntitySectionStorageMixin<T extends EntityAccess> {
	@Unique private static final int MAX_DIRECT_PER_COLUMN = 64;
	/** -Dbasalt.entitysections.verify=true compares the visited sections with vanilla on every call. */
	@Unique private static final boolean VERIFY = Boolean.getBoolean("basalt.entitysections.verify");

	@Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;

	@WrapMethod(method = "forEachAccessibleNonEmptySection")
	private void basalt$directLookup(AABB bb, AbortableIterationConsumer<EntitySection<T>> output, Operation<Void> original) {
		int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
		int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
		int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
		int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
		int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
		int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
		long perColumn = (long) (yMax - yMin + 1) * (zMax - zMin + 1);
		if (perColumn <= 0 || perColumn > MAX_DIRECT_PER_COLUMN || xMax < xMin) {
			original.call(bb, output);
			return;
		}

		if (VERIFY) {
			List<EntitySection<T>> expected = new ArrayList<>();
			original.call(bb, (AbortableIterationConsumer<EntitySection<T>>) s -> {
				expected.add(s);
				return Continuation.CONTINUE;
			});
			List<EntitySection<T>> actual = new ArrayList<>();
			this.basalt$visit(xMin, yMin, zMin, xMax, yMax, zMax, (int) perColumn, s -> {
				actual.add(s);
				return Continuation.CONTINUE;
			});
			if (expected.size() != actual.size()) {
				throw new IllegalStateException("Basalt entitysections mismatch: " + expected.size() + " vs " + actual.size() + " sections for " + bb);
			}
			for (int i = 0; i < expected.size(); i++) {
				if (expected.get(i) != actual.get(i)) {
					throw new IllegalStateException("Basalt entitysections order mismatch at " + i + " for " + bb);
				}
			}
		}

		this.basalt$visit(xMin, yMin, zMin, xMax, yMax, zMax, (int) perColumn, output);
	}

	@Unique
	private void basalt$visit(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, int perColumn, AbortableIterationConsumer<EntitySection<T>> output) {
		long[] keys = new long[perColumn];
		for (int x = xMin; x <= xMax; x++) {
			int n = 0;
			for (int z = zMin; z <= zMax; z++) {
				for (int y = yMin; y <= yMax; y++) {
					keys[n++] = SectionPos.asLong(x, y, z);
				}
			}
			// Same order as vanilla's LongAVLTreeSet walk: keys share the X prefix, so signed order = vanilla order.
			Arrays.sort(keys, 0, n);
			for (int i = 0; i < n; i++) {
				EntitySection<T> section = this.sections.get(keys[i]);
				if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && output.accept(section).shouldAbort()) {
					return;
				}
			}
		}
	}
}
