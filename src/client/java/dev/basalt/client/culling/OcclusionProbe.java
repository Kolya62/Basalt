package dev.basalt.client.culling;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Synchronous access to the occlusion ray test (for measurements). */
public final class OcclusionProbe {
	private OcclusionProbe() {
	}

	public static boolean visible(ClientLevel level, Vec3 cam, AABB box) {
		return OcclusionCuller.isVisible(level, new BlockPos.MutableBlockPos(), cam.x, cam.y, cam.z, box);
	}
}
