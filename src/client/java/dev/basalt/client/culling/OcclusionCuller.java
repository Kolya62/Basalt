package dev.basalt.client.culling;

import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Hides entities and block entities that are fully behind opaque blocks, so the render thread
 * skips extracting, animating and drawing them. Visibility is computed on a background thread by
 * tracing rays from the camera to the corners and centre of each object's box through the block
 * grid; an object is culled only if every ray hits an opaque full block. Results lag by a few
 * milliseconds, so newly requested objects start visible and boxes are padded.
 * <p>
 * An object is only traced again when something its answer depends on changed: the camera or the
 * object moved (by a quarter block), or a block changed opacity / a chunk (un)loaded anywhere
 * ({@link #worldChanged}). A slow fallback re-trace covers anything else. A static scene therefore
 * costs almost nothing per pass instead of a full core.
 */
public final class OcclusionCuller {
	private static final int MAX_STEPS = Integer.parseInt(BasaltConfig.get("entityculling.max_distance", "128"));
	private static final long STALE_MS = 1000;
	/** Re-trace unchanged objects at least this often (must stay below STALE_MS). */
	private static final long FALLBACK_MS = 500;
	private static final long SLEEP_MS = 10;
	private static final long IDLE_SLEEP_MS = 50;
	private static final double ALWAYS_VISIBLE_DIST_SQR = 4.0 * 4.0;

	private static final Set<Cullable> TRACKED = ConcurrentHashMap.newKeySet();
	private static final AtomicLong WORLD_EPOCH = new AtomicLong();
	private static volatile double camX, camY, camZ;
	private static volatile @Nullable ClientLevel level;
	/** Render thread: the frame's time, read once per frame instead of once per object. */
	private static long frameMillis;
	private static Thread thread;

	private OcclusionCuller() {
	}

	/** Called on the render thread once per frame. */
	public static void updateCamera(ClientLevel lvl, double x, double y, double z) {
		level = lvl;
		camX = x;
		camY = y;
		camZ = z;
		frameMillis = Util.getMillis();
		if (thread == null) {
			thread = new Thread(OcclusionCuller::run, "Basalt Occlusion Culler");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			thread.start();
		}
	}

	/** The level was left or replaced: forget it and everything traced in it. */
	public static void reset() {
		level = null;
		TRACKED.clear();
		WORLD_EPOCH.incrementAndGet();
	}

	/** A block changed opacity or a chunk was (un)loaded: every cached answer may be wrong. */
	public static void worldChanged() {
		WORLD_EPOCH.incrementAndGet();
	}

	/** Render thread: should this object be skipped? Also registers it for tracing. */
	public static boolean isCulled(Object object) {
		if (!(object instanceof Cullable cullable)) {
			return false;
		}
		long now = frameMillis;
		if (now - cullable.basalt$lastRequested() > STALE_MS) {
			// Not looked at recently: its old result may be wrong, start visible again.
			cullable.basalt$setCulled(false);
			TRACKED.add(cullable);
		}
		cullable.basalt$setLastRequested(now);
		return cullable.basalt$isCulled();
	}

	private static void run() {
		SectionCursor cursor = new SectionCursor();
		while (true) {
			try {
				long now = Util.getMillis();
				ClientLevel lvl = level;
				double cx = camX, cy = camY, cz = camZ;
				long epoch = WORLD_EPOCH.get();
				cursor.reset(lvl);
				for (Cullable c : TRACKED) {
					if (lvl == null || now - c.basalt$lastRequested() > STALE_MS) {
						TRACKED.remove(c);
						continue;
					}
					AABB box = boxOf(c);
					if (box == null) {
						c.basalt$setCulled(false);
						c.basalt$setTraced(0L, 0L);
						continue;
					}
					long key = traceKey(cx, cy, cz, box, epoch);
					if (key == c.basalt$tracedKey() && now - c.basalt$tracedAt() < FALLBACK_MS) {
						continue; // nothing it depends on changed
					}
					c.basalt$setCulled(!isVisible(cursor, cx, cy, cz, box));
					c.basalt$setTraced(key, now);
				}
				Thread.sleep(TRACKED.isEmpty() ? IDLE_SLEEP_MS : SLEEP_MS);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				// The level is read concurrently with the render thread; a torn read just means we retry.
				Basalt.LOGGER.debug("Occlusion pass failed", t);
			}
		}
	}

	private static long traceKey(double cx, double cy, double cz, AABB box, long epoch) {
		long h = epoch * 0x9E3779B97F4A7C15L;
		h = mix(h, quarter(cx));
		h = mix(h, quarter(cy));
		h = mix(h, quarter(cz));
		h = mix(h, quarter(box.minX));
		h = mix(h, quarter(box.minY));
		h = mix(h, quarter(box.minZ));
		h = mix(h, quarter(box.maxY));
		return h == 0L ? 1L : h; // 0 means "never traced"
	}

	private static long quarter(double v) {
		return (long) Math.floor(v * 4.0);
	}

	private static long mix(long h, long v) {
		h = (h ^ v) * 0xBF58476D1CE4E5B9L;
		return h ^ h >>> 31;
	}

	private static @Nullable AABB boxOf(Cullable c) {
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

	static boolean isVisible(SectionCursor cursor, double cx, double cy, double cz, AABB b) {
		double nx = Math.clamp(cx, b.minX, b.maxX), ny = Math.clamp(cy, b.minY, b.maxY), nz = Math.clamp(cz, b.minZ, b.maxZ);
		double dx = nx - cx, dy = ny - cy, dz = nz - cz;
		if (dx * dx + dy * dy + dz * dz < ALWAYS_VISIBLE_DIST_SQR) {
			return true;
		}
		double mx = (b.minX + b.maxX) * 0.5, my = (b.minY + b.maxY) * 0.5, mz = (b.minZ + b.maxZ) * 0.5;
		if (rayClear(cursor, cx, cy, cz, mx, my, mz)) {
			return true;
		}
		for (int i = 0; i < 8; i++) {
			double tx = (i & 1) == 0 ? b.minX : b.maxX;
			double ty = (i & 2) == 0 ? b.minY : b.maxY;
			double tz = (i & 4) == 0 ? b.minZ : b.maxZ;
			if (rayClear(cursor, cx, cy, cz, tx, ty, tz)) {
				return true;
			}
		}
		return false;
	}

	/** Amanatides-Woo voxel walk; true if no opaque full block lies strictly between the endpoints. */
	private static boolean rayClear(SectionCursor cursor, double x0, double y0, double z0, double x1, double y1, double z1) {
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
			if (cursor.isSolid(x, y, z)) {
				return false;
			}
		}
		return true; // too far to judge: never cull
	}

	/**
	 * Block lookups along a ray, remembering the current 16^3 section: a ray stays in one section
	 * for many steps, and air-only or unloaded sections are skipped without any lookup.
	 */
	static final class SectionCursor {
		private @Nullable ClientLevel level;
		private int sectionX, sectionY, sectionZ;
		private @Nullable LevelChunkSection section;

		void reset(@Nullable ClientLevel level) {
			this.level = level;
			this.sectionX = Integer.MIN_VALUE;
			this.section = null;
		}

		boolean isSolid(int x, int y, int z) {
			int sx = x >> 4, sy = y >> 4, sz = z >> 4;
			if (sx != this.sectionX || sy != this.sectionY || sz != this.sectionZ) {
				this.sectionX = sx;
				this.sectionY = sy;
				this.sectionZ = sz;
				this.section = this.lookup(sx, sy, sz);
			}
			LevelChunkSection s = this.section;
			return s != null && s.getBlockState(x & 15, y & 15, z & 15).isSolidRender();
		}

		private @Nullable LevelChunkSection lookup(int sx, int sy, int sz) {
			ClientLevel lvl = this.level;
			if (lvl == null) {
				return null;
			}
			LevelChunk chunk = lvl.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
			if (chunk == null) {
				return null;
			}
			LevelChunkSection[] sections = chunk.getSections();
			int index = lvl.getSectionIndexFromSectionY(sy);
			if (index < 0 || index >= sections.length) {
				return null;
			}
			LevelChunkSection s = sections[index];
			return s == null || s.hasOnlyAir() ? null : s;
		}
	}
}
