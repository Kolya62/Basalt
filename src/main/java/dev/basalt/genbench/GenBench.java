package dev.basalt.genbench;

import dev.basalt.Basalt;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * World-generation benchmark, active only with -Dbasalt.genbench=&lt;label&gt; on a fresh world.
 * Force-loads an N x N chunk square far from spawn, times how long until every chunk is FULL,
 * then fingerprints every block so runs can be checked for block-identical output.
 */
public final class GenBench {
	public static final String LABEL = System.getProperty("basalt.genbench");
	private static final int SIZE = Integer.getInteger("basalt.genbench.size", 32);
	private static final int ORIGIN = Integer.getInteger("basalt.genbench.origin", 1000); // in chunks, away from spawn chunks

	/** -Dbasalt.genbench.status=terrain stops at that status (terrain must be bit-identical run to run). */
	private static final ChunkStatus STATUS = ChunkStatus.byName(System.getProperty("basalt.genbench.status", "full"));

	private static long startNs = -1;
	private static long cpuStartNs;
	private static boolean done;

	private GenBench() {
	}

	public static void tick(MinecraftServer server) {
		if (done) {
			return;
		}
		ServerLevel level = server.overworld();
		ServerChunkCache chunks = level.getChunkSource();
		if (startNs < 0) {
			startNs = System.nanoTime();
			cpuStartNs = cpuTime();
			for (int x = 0; x < SIZE; x++) {
				for (int z = 0; z < SIZE; z++) {
					// Loading-only ticket at FULL level: chunks are fully generated but never ticked, so
					// fluids, random ticks etc. cannot change blocks after generation.
					chunks.addTicket(new Ticket(TicketType.PLAYER_LOADING, ChunkLevel.byStatus(STATUS)), new ChunkPos(ORIGIN + x, ORIGIN + z));
				}
			}
			Basalt.LOGGER.info("[genbench] requested {} chunks", SIZE * SIZE);
			return;
		}

		int ready = 0;
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				if (get(chunks, ORIGIN + x, ORIGIN + z) != null) {
					ready++;
				}
			}
		}
		if (ready < SIZE * SIZE) {
			return;
		}

		done = true;
		double seconds = (System.nanoTime() - startNs) / 1e9;
		double cores = (cpuTime() - cpuStartNs) / 1e9 / seconds;
		long fingerprint = fingerprint(chunks);
		dumpChunks(chunks);
		dumpHistogram(chunks);
		String dump = System.getProperty("basalt.genbench.dumpBlocks");
		if (dump != null) {
			String[] p = dump.split(",");
			try {
				Files.writeString(Path.of("basalt-genbench", LABEL + ".blocks"), blocksOf(get(chunks, Integer.parseInt(p[0]), Integer.parseInt(p[1]))));
			} catch (IOException e) {
				Basalt.LOGGER.error("[genbench] failed to dump blocks", e);
			}
		}
		String report = String.format(Locale.ROOT, "label=%s%nstatus=" + STATUS.getName() + "%nchunks=%d%nseconds=%.2f%nchunks_per_s=%.1f%ncpu_cores_used=%.2f%nfingerprint=%016x%n",
			LABEL, SIZE * SIZE, seconds, SIZE * SIZE / seconds, cores, fingerprint);
		try {
			Path out = Path.of("basalt-genbench", LABEL + ".txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, report);
		} catch (IOException e) {
			Basalt.LOGGER.error("[genbench] failed to write report", e);
		}
		Basalt.LOGGER.info("[genbench] result:\n{}", report);
		server.halt(false);
	}

	/** Order-independent per chunk (chunks hashed then combined in fixed x/z order). */
	private static long fingerprint(ServerChunkCache chunks) {
		long h = 0x9E3779B97F4A7C15L;
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				ChunkAccess chunk = get(chunks, ORIGIN + x, ORIGIN + z);
				long c = 1;
				for (LevelChunkSection section : chunk.getSections()) {
					for (int y = 0; y < 16; y++) {
						for (int zz = 0; zz < 16; zz++) {
							for (int xx = 0; xx < 16; xx++) {
								c = c * 1_000_003L + Block.getId(section.getBlockState(xx, y, zz));
							}
						}
					}
				}
				h = Long.rotateLeft(h ^ c, 23) * 0xBF58476D1CE4E5B9L;
			}
		}
		return h;
	}

	/**
	 * Writes basalt-genbench/&lt;label&gt;.chunks: per chunk, a hash per section, so two runs can be
	 * diffed to see which chunks/sections differ, plus the block ids of differing sections for drill-down.
	 */
	private static void dumpChunks(ServerChunkCache chunks) {
		StringBuilder sb = new StringBuilder();
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				ChunkAccess chunk = get(chunks, ORIGIN + x, ORIGIN + z);
				sb.append(ORIGIN + x).append(',').append(ORIGIN + z);
				LevelChunkSection[] sections = chunk.getSections();
				for (int s = 0; s < sections.length; s++) {
					long c = 1;
					for (int y = 0; y < 16; y++) {
						for (int zz = 0; zz < 16; zz++) {
							for (int xx = 0; xx < 16; xx++) {
								c = c * 1_000_003L + Block.getId(sections[s].getBlockState(xx, y, zz));
							}
						}
					}
					sb.append(' ').append(Long.toHexString(c));
				}
				sb.append('\n');
			}
		}
		try {
			Files.writeString(Path.of("basalt-genbench", LABEL + ".chunks"), sb);
		} catch (IOException e) {
			Basalt.LOGGER.error("[genbench] failed to write chunk dump", e);
		}
	}

	/** basalt-genbench/&lt;label&gt;.hist: "x,z blockName count" for every block type in every chunk. */
	private static void dumpHistogram(ServerChunkCache chunks) {
		StringBuilder sb = new StringBuilder();
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				ChunkAccess chunk = get(chunks, ORIGIN + x, ORIGIN + z);
				it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
				for (LevelChunkSection section : chunk.getSections()) {
					for (int y = 0; y < 16; y++) {
						for (int zz = 0; zz < 16; zz++) {
							for (int xx = 0; xx < 16; xx++) {
								counts.addTo(section.getBlockState(xx, y, zz).getBlock().builtInRegistryHolder().getRegisteredName(), 1);
							}
						}
					}
				}
				int cx = ORIGIN + x, cz = ORIGIN + z;
				counts.forEach((name, n) -> sb.append(cx).append(',').append(cz).append(' ').append(name).append(' ').append(n).append('\n'));
			}
		}
		try {
			Files.writeString(Path.of("basalt-genbench", LABEL + ".hist"), sb);
		} catch (IOException e) {
			Basalt.LOGGER.error("[genbench] failed to write histogram", e);
		}
	}

	/** -Dbasalt.genbench.dumpBlocks=x,z: also dump every block of that chunk (for diffing what differs). */
	private static ChunkAccess get(ServerChunkCache chunks, int x, int z) {
		return STATUS == ChunkStatus.FULL ? chunks.getChunkNow(x, z) : chunks.getChunk(x, z, STATUS, false);
	}

	public static String blocksOf(ChunkAccess chunk) {
		StringBuilder sb = new StringBuilder();
		LevelChunkSection[] sections = chunk.getSections();
		for (int s = 0; s < sections.length; s++) {
			for (int y = 0; y < 16; y++) {
				for (int zz = 0; zz < 16; zz++) {
					for (int xx = 0; xx < 16; xx++) {
						net.minecraft.world.level.block.state.BlockState st = sections[s].getBlockState(xx, y, zz);
						if (!st.isAir()) {
							sb.append(xx).append(' ').append(chunk.getMinY() + s * 16 + y).append(' ').append(zz).append(' ').append(st).append('\n');
						}
					}
				}
			}
		}
		return sb.toString();
	}

	private static long cpuTime() {
		return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
	}
}
