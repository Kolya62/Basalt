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
 * <p>
 * One cached layer per resolver: grass, foliage and water tints are requested interleaved within a
 * layer, and a single shared slot was reset on every switch between them.
 */
public final class TintBlender {
	/** Bumped whenever vanilla invalidates its tint caches (chunk loads, setting changes). */
	public static final AtomicInteger EPOCH = new AtomicInteger();
	private static final ThreadLocal<TintBlender> LOCAL = ThreadLocal.withInitial(TintBlender::new);
	/** Vanilla has three resolvers (grass, foliage, water); a fourth replaces the oldest slot. */
	private static final int SLOTS = 3;

	private final Layer[] layers = new Layer[SLOTS];
	private int nextVictim;
	private final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();

	private static final boolean VERIFY = Boolean.getBoolean("basalt.tint.verify");
	public static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();

	/** Sampled colours of one chunk layer for one resolver. */
	private static final class Layer {
		Level level;
		ColorResolver resolver;
		int radius = -1, chunkX, chunkZ, y, epoch;
		int size;
		/** Sampled colour per grid position of the current chunk layer, or 0 if not sampled yet (colours have alpha 0xFF). */
		int[] colors = new int[0];
	}

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

	private Layer layerFor(ColorResolver resolver) {
		for (Layer layer : this.layers) {
			if (layer != null && layer.resolver == resolver) {
				return layer;
			}
		}
		int slot = this.nextVictim;
		this.nextVictim = (slot + 1) % SLOTS;
		Layer layer = this.layers[slot];
		if (layer == null) {
			layer = this.layers[slot] = new Layer();
		}
		layer.resolver = resolver;
		layer.level = null; // forces a reset below
		return layer;
	}

	private int get(Level level, BlockPos pos, ColorResolver resolver, int radius) {
		Layer layer = this.layerFor(resolver);
		int cx = SectionPos.blockToSectionCoord(pos.getX());
		int cz = SectionPos.blockToSectionCoord(pos.getZ());
		int e = EPOCH.get();
		if (level != layer.level || radius != layer.radius || cx != layer.chunkX || cz != layer.chunkZ || pos.getY() != layer.y || e != layer.epoch) {
			layer.level = level;
			layer.radius = radius;
			layer.chunkX = cx;
			layer.chunkZ = cz;
			layer.y = pos.getY();
			layer.epoch = e;
			layer.size = 16 + 2 * radius;
			int n = layer.size * layer.size;
			if (layer.colors.length < n) {
				layer.colors = new int[n];
			} else {
				java.util.Arrays.fill(layer.colors, 0, n, 0);
			}
		}
		int[] colors = layer.colors;
		int size = layer.size;
		int y = layer.y;
		int x0 = (cx << 4) - radius, z0 = (cz << 4) - radius;
		int lx = pos.getX() - (cx << 4), lz = pos.getZ() - (cz << 4);
		int d = 2 * radius + 1;
		int r = 0, g = 0, b = 0;
		for (int gz = lz; gz < lz + d; gz++) {
			int row = gz * size;
			for (int gx = lx; gx < lx + d; gx++) {
				int color = colors[row + gx];
				if (color == 0) {
					int x = x0 + gx, z = z0 + gz;
					this.sample.set(x, y, z);
					color = resolver.getColor((Biome) level.getBiome(this.sample).value(), x, z) | 0xFF000000;
					colors[row + gx] = color;
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
