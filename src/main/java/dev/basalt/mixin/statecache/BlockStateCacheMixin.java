package dev.basalt.mixin.statecache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block-state cache "face sturdiness" (6 directions x 3 support types, each a VoxelShape join):
 * vanilla computes it for all ~30k states in the single-threaded Blocks bootstrap, ~0.5 s. It is
 * only read through isFaceSturdy, so compute it on first use per state (same inputs, same values)
 * and share identical patterns between states.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
public class BlockStateCacheMixin {
	@Unique private static final ConcurrentHashMap<Integer, boolean[]> BASALT_STURDY = new ConcurrentHashMap<>();
	@Unique private static final Direction[] BASALT_DIRECTIONS = Direction.values();
	@Unique private static final SupportType[] BASALT_TYPES = SupportType.values();

	@org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final @org.spongepowered.asm.mixin.Mutable private boolean[] faceSturdy;

	@Unique private BlockState basalt$state;
	@Unique private volatile boolean[] basalt$sturdy;

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/SupportType;isSupporting(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"))
	private boolean basalt$deferSturdy(SupportType type, BlockState state, BlockGetter level, BlockPos pos, Direction direction, Operation<Boolean> original) {
		return false; // computed lazily in isFaceSturdy
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void basalt$rememberState(BlockState state, CallbackInfo ci) {
		this.basalt$state = state;
		this.faceSturdy = null; // vanilla's eager array is never read now
	}

	/**
	 * @author Basalt
	 * @reason Lazily computed, shared face-sturdiness table (identical values to vanilla).
	 */
	@Overwrite
	public boolean isFaceSturdy(final Direction direction, final SupportType supportType) {
		boolean[] sturdy = this.basalt$sturdy;
		if (sturdy == null) {
			sturdy = this.basalt$compute();
		}
		return sturdy[direction.ordinal() * BASALT_TYPES.length + supportType.ordinal()];
	}

	@Unique
	private boolean[] basalt$compute() {
		boolean[] own = new boolean[BASALT_DIRECTIONS.length * BASALT_TYPES.length];
		int key = own.length << 24;
		for (Direction direction : BASALT_DIRECTIONS) {
			for (SupportType type : BASALT_TYPES) {
				int i = direction.ordinal() * BASALT_TYPES.length + type.ordinal();
				own[i] = type.isSupporting(this.basalt$state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction);
				if (own[i]) {
					key |= 1 << i;
				}
			}
		}
		boolean[] shared = BASALT_STURDY.computeIfAbsent(key, k -> own);
		this.basalt$sturdy = shared;
		return shared;
	}
}
