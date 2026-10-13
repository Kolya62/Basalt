package dev.basalt.client.culling;

import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Hides entities and block entities that are fully behind opaque blocks, so the render thread
 * skips extracting, animating and drawing them. Visibility is computed on a background thread by
 * tracing rays from the camera to the corners and centre of each object's box through the block
 * grid; an object is culled only if every ray hits an opaque full block. Results lag by a few
 * milliseconds, so newly requested objects start visible and boxes are padded.
 */
public final class OcclusionCuller {
	private static final int MAX_STEPS = Integer.parseInt(BasaltConfig.get("entityculling.max_distance", "128"));
	private static final long STALE_MS = 1000;
	private static final long SLEEP_MS = 10;
	private static final double ALWAYS_VISIBLE_DIST_SQR = 4.0 * 4.0;

	private static final Set<Cullable> TRACKED = ConcurrentHashMap.newKeySet();
	private static volatile double camX, camY, camZ;
	private static volatile ClientLevel level;
	private static Thread thread;

	private OcclusionCuller() {
	}

	/** Called on the render thread once per frame. */
	public static void updateCamera(ClientLevel lvl, double x, double y, double z) {
		level = lvl;
		camX = x;
		camY = y;
		camZ = z;
		if (thread == null) {
			thread = new Thread(OcclusionCuller::run, "Basalt Occlusion Culler");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			thread.start();
		}
	}

	/** Render thread: should this object be skipped? Also registers it for tracing. */
	public static boolean isCulled(Object object) {
		if (!(object instanceof Cullable cullable)) {
			return false;
		}
		long now = Util.getMillis();
		if (now - cullable.basalt$lastRequested() > STALE_MS) {
			// Not looked at recently: its old result may be wrong, start visible again.
			cullable.basalt$setCulled(false);
			TRACKED.add(cullable);
		}
		cullable.basalt$setLastRequested(now);
		return cullable.basalt$isCulled();
	}

	private static void run() {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		while (true) {
			try {
				long now = Util.getMillis();
				ClientLevel lvl = level;
				double cx = camX, cy = camY, cz = camZ;
				for (Cullable c : TRACKED) {
					if (now - c.basalt$lastRequested() > STALE_MS || lvl == null) {
						TRACKED.remove(c);
						continue;
					}
					AABB box = boxOf(c);
					c.basalt$setCulled(box != null && !isVisible(lvl, cursor, cx, cy, cz, box));
				}
				Thread.sleep(SLEEP_MS);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				// The level is read concurrently with the render thread; a torn read just means we retry.
				Basalt.LOGGER.debug("Occlusion pass failed", t);
			}
		}
	}

	private static AABB boxOf(Cullable c) {
		if (c instanceof Entity e) {
			if (e.isRemoved() || e.isCurrentlyGlowing() || Minecraft.getInstance().player != null
				&& (e.hasIndirectPassenger(Minecraft.getInstance().player) || e == Minecraft.getInstance().player)) {
				return null;
			}
			// pad for name tags, held items and interpolation lag
			return e.getBoundingBox().inflate(0.5).expandTowards(0, 0.75, 0);
		}
		if (c instanceof BlockEntity be) {
			BlockPos p = be.getBlockPos();
			return new AABB(p).inflate(0.5);
		}
		return null;
	}

	static boolean isVisible(ClientLevel lvl, BlockPos.MutableBlockPos cursor, double cx, double cy, double cz, AABB b) {
		double nx = Math.clamp(cx, b.minX, b.maxX), ny = Math.clamp(cy, b.minY, b.maxY), nz = Math.clamp(cz, b.minZ, b.maxZ);
		double dx = nx - cx, dy = ny - cy, dz = nz - cz;
		if (dx * dx + dy * dy + dz * dz < ALWAYS_VISIBLE_DIST_SQR) {
			return true;
		}
		double mx = (b.minX + b.maxX) * 0.5, my = (b.minY + b.maxY) * 0.5, mz = (b.minZ + b.maxZ) * 0.5;
		if (rayClear(lvl, cursor, cx, cy, cz, mx, my, mz)) {
			return true;
		}
		for (int i = 0; i < 8; i++) {
			double tx = (i & 1) == 0 ? b.minX : b.maxX;
			double ty = (i & 2) == 0 ? b.minY : b.maxY;
			double tz = (i & 4) == 0 ? b.minZ : b.maxZ;
			if (rayClear(lvl, cursor, cx, cy, cz, tx, ty, tz)) {
				return true;
			}
		}
		return false;
	}

	/** Amanatides-Woo voxel walk; true if no opaque full block lies strictly between the endpoints. */
	private static boolean rayClear(ClientLevel lvl, BlockPos.MutableBlockPos cursor, double x0, double y0, double z0, double x1, double y1, double z1) {
		int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
		int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
		double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
		double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
		double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
		double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
		double tmx = sx == 0 ? Double.MAX_VALUE : tdx * (sx > 0 ? x + 1 - x0 : x0 - x);
		double tmy = sy == 0 ? Double.MAX_VALUE : tdy * (sy > 0 ? y + 1 - y0 : y0 - y);
		double tmz = sz == 0 ? Double.MAX_VALUE : tdz * (sz > 0 ? z + 1 - z0 : z0 - z);

		for (int steps = 0; steps < MAX_STEPS * 3; steps++) {
			if (tmx < tmy && tmx < tmz) {
				x += sx;
				tmx += tdx;
			} else if (tmy < tmz) {
				y += sy;
				tmy += tdy;
			} else {
				z += sz;
				tmz += tdz;
			}
			if (x == ex && y == ey && z == ez) {
				return true;
			}
			BlockState state = lvl.getBlockState(cursor.set(x, y, z));
			if (state.isSolidRender()) {
				return false;
			}
		}
		return true; // too far to judge: never cull
	}
}
