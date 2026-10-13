package dev.basalt.mixin.collisions;

import com.google.common.collect.AbstractIterator;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Entity/block collision search, run for every moving entity every tick on both client and server.
 * Same iteration and results as vanilla, with two shortcuts:
 * air is skipped before asking for its collision shape, and a partial shape whose bounds do not
 * overlap the entity box with positive volume is rejected before the allocating move() and the
 * exact voxel join (an AND join of shapes whose bounds do not overlap is always empty).
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin<T> extends AbstractIterator<T> {
	@Unique private static final boolean VERIFY = Boolean.getBoolean("basalt.collisions.verify");

	@Shadow @Final private AABB box;
	@Shadow @Final private CollisionContext context;
	@Shadow @Final private Cursor3D cursor;
	@Shadow @Final private BlockPos.MutableBlockPos pos;
	@Shadow @Final private VoxelShape entityShape;
	@Shadow @Final private CollisionGetter collisionGetter;
	@Shadow @Final private boolean onlySuffocatingBlocks;
	@Shadow @Final private BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> resultProvider;

	@Shadow
	private @Nullable BlockGetter getChunk(int x, int z) {
		throw new AssertionError();
	}

	/**
	 * @author Basalt
	 * @reason skip air and non-overlapping partial shapes without allocating
	 */
	@Overwrite
	protected T computeNext() {
		while (this.cursor.advance()) {
			int x = this.cursor.nextX();
			int y = this.cursor.nextY();
			int z = this.cursor.nextZ();
			int cursorFaceType = this.cursor.getNextType();
			if (cursorFaceType == 3) {
				continue;
			}
			BlockGetter chunk = this.getChunk(x, z);
			if (chunk == null) {
				continue;
			}
			this.pos.set(x, y, z);
			BlockState blockState = chunk.getBlockState(this.pos);
			if (blockState.isAir()) {
				continue; // air has an empty collision shape in every context
			}
			if ((!this.onlySuffocatingBlocks || blockState.isSuffocating(chunk, this.pos))
				&& (cursorFaceType != 1 || blockState.hasLargeCollisionShape())
				&& (cursorFaceType != 2 || blockState.is(Blocks.MOVING_PISTON))) {
				VoxelShape blockShape = this.context.getCollisionShape(blockState, this.collisionGetter, this.pos);
				if (blockShape == Shapes.block()) {
					if (this.box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
						return this.resultProvider.apply(this.pos, blockShape.move(this.pos));
					}
				} else if (!blockShape.isEmpty()) {
					boolean overlaps = blockShape.max(Direction.Axis.X) + x > this.box.minX && blockShape.min(Direction.Axis.X) + x < this.box.maxX
						&& blockShape.max(Direction.Axis.Y) + y > this.box.minY && blockShape.min(Direction.Axis.Y) + y < this.box.maxY
						&& blockShape.max(Direction.Axis.Z) + z > this.box.minZ && blockShape.min(Direction.Axis.Z) + z < this.box.maxZ;
					if (!overlaps) {
						if (VERIFY && Shapes.joinIsNotEmpty(blockShape.move(this.pos), this.entityShape, BooleanOp.AND)) {
							throw new IllegalStateException("basalt collisions: bounds rejected a colliding shape " + blockState + " at " + this.pos);
						}
						continue;
					}
					VoxelShape shape = blockShape.move(this.pos);
					if (Shapes.joinIsNotEmpty(shape, this.entityShape, BooleanOp.AND)) {
						return this.resultProvider.apply(this.pos, shape);
					}
				}
			}
		}
		return this.endOfData();
	}
}
