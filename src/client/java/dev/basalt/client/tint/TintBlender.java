package dev.basalt.client.tint;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome tint blending (grass, foliage, water) for chunk meshing. Vanilla averages the colour of
 * (2r+1)^2 positions around every block, i.e. 25 biome lookups per block at the default radius,
 * although neighbouring blocks share almost all of them. This remembers each sampled position of
 * the current chunk layer, so every position is looked up at most once per layer and only when a
 * tinted block needs it. Same samples, same integer sums: the result equals vanilla exactly.
 */
public final class TintBlender {
	/** Bumped whenever vanilla invalidates its tint caches (chunk loads, setting changes). */
	public static final AtomicInteger EPOCH = new AtomicInteger();
	private static final ThreadLocal<TintBlender> LOCAL = ThreadLocal.withInitial(TintBlender::new);

	private Level level;
	private ColorResolver resolver;
	private int radius = -1, chunkX, chunkZ, y, epoch;
	private int size;
	/** Sampled colour per grid position of the current chunk layer, or 0 if not sampled yet (colours have alpha 0xFF). */
	private int[] colors = new int[0];
	private final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();

	private static final boolean VERIFY = Boolean.getBoolean("basalt.tint.verify");
	public static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();

	public static int blend(Level level, BlockPos pos, ColorResolver resolver, int radius) {
		int color = LOCAL.get().get(level, pos, resolver, radius);
		if (VERIFY) {
			int expected = vanilla(level, pos, resolver, radius);
			if (expected != color) {
				throw new IllegalStateException("basalt tint mismatch at " + pos + ": " + Integer.toHexString(color) + " vs " + Integer.toHexString(expected));
			}
			if (VERIFIED.incrementAndGet() % 50_000 == 0) {
				dev.basalt.Basalt.LOGGER.info("tint verified {} blocks", VERIFIED.get());
			}
		}
		return color;
	}

	/** ClientLevel.calculateBlockTint, verbatim, for verification. */
	private static int vanilla(Level level, BlockPos pos, ColorResolver resolver, int dist) {
		int count = (dist * 2 + 1) * (dist * 2 + 1);
		int r = 0, g = 0, b = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int z = pos.getZ() - dist; z <= pos.getZ() + dist; z++) {
			for (int x = pos.getX() - dist; x <= pos.getX() + dist; x++) {
				p.set(x, pos.getY(), z);
				int c = resolver.getColor((Biome) level.getBiome(p).value(), p.getX(), p.getZ());
				r += ARGB.red(c);
				g += ARGB.green(c);
				b += ARGB.blue(c);
			}
		}
		return ARGB.color(r / count, g / count, b / count);
	}

	private int get(Level level, BlockPos pos, ColorResolver resolver, int radius) {
		int cx = SectionPos.blockToSectionCoord(pos.getX());
		int cz = SectionPos.blockToSectionCoord(pos.getZ());
		int e = EPOCH.get();
		if (level != this.level || resolver != this.resolver || radius != this.radius || cx != this.chunkX || cz != this.chunkZ || pos.getY() != this.y || e != this.epoch) {
			this.level = level;
			this.resolver = resolver;
			this.radius = radius;
			this.chunkX = cx;
			this.chunkZ = cz;
			this.y = pos.getY();
			this.epoch = e;
			this.size = 16 + 2 * radius;
			int n = this.size * this.size;
			if (this.colors.length < n) {
				this.colors = new int[n];
			} else {
				java.util.Arrays.fill(this.colors, 0, n, 0);
			}
		}
		int x0 = (cx << 4) - radius, z0 = (cz << 4) - radius;
		int lx = pos.getX() - (cx << 4), lz = pos.getZ() - (cz << 4);
		int d = 2 * radius + 1;
		int r = 0, g = 0, b = 0;
		for (int gz = lz; gz < lz + d; gz++) {
			int row = gz * this.size;
			for (int gx = lx; gx < lx + d; gx++) {
				int color = this.colors[row + gx];
				if (color == 0) {
					int x = x0 + gx, z = z0 + gz;
					this.sample.set(x, this.y, z);
					color = resolver.getColor((Biome) level.getBiome(this.sample).value(), x, z) | 0xFF000000;
					this.colors[row + gx] = color;
				}
				r += ARGB.red(color);
				g += ARGB.green(color);
				b += ARGB.blue(color);
			}
		}
		int count = d * d;
		return ARGB.color(r / count, g / count, b / count);
	}
}
