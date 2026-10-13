package dev.basalt.client.bench;

import dev.basalt.Basalt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;

/**
 * -Dbasalt.bench.scene=farm builds a deterministic server-load scene next to the player:
 * an animal pen, a villager pen, hopper chains carrying items and loose item entities.
 */
public final class Scene {
	public static final String NAME = System.getProperty("basalt.bench.scene", "");

	private Scene() {
	}

	public static void build(IntegratedServer server, BlockPos origin) {
		Random random = new Random(1234);
		int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
		List<String> cmds = new ArrayList<>();
		cmds.add("gamerule spawn_mobs false");
		cmds.add("gamerule advance_time false");
		cmds.add("time set noon");
		cmds.add("weather clear 1000000");
		switch (NAME) {
			case "farm" -> farm(cmds, random, ox, oy, oz);
			case "storage" -> storage(cmds, random, ox, oy, oz);
			case "cave" -> cave(cmds, random, ox, oy, oz);
			case "ui" -> ui(cmds, random, ox, oy, oz);
			case "explore" -> cmds.add("gamerule spawn_mobs true");
			case "still" -> {
				// deterministic still image: no time/weather change, no mobs
				cmds.add("kill @e[type=!player]");
				cmds.add("gamerule advance_weather false");
			}
			default -> {
				return;
			}
		}

		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.execute(() -> {
			for (String cmd : cmds) {
				server.getCommands().performPrefixedCommand(source, cmd);
			}
			Basalt.LOGGER.info("[bench] scene '{}' built with {} commands", NAME, cmds.size());
		});
	}

	private static long lastTeleportNs;
	private static int teleports;

	/** Called every frame while recording: the explore scene teleports into fresh terrain every 4 s. */
	public static void tick(IntegratedServer server, long now) {
		if (!NAME.equals("explore") || now - lastTeleportNs < 4_000_000_000L) {
			return;
		}
		lastTeleportNs = now;
		int x = 2000 + 512 * ++teleports;
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.execute(() -> server.getCommands().performPrefixedCommand(source, f("tp @p %d 200 0", x)));
	}

	public static int teleports() {
		return teleports;
	}

	/**
	 * Hidden mobs: a solid stone wall fills the view (camera faces +z), and behind it a cleared
	 * hall with 300 motionless mobs. Every mob is in the frustum but none is visible, so this
	 * measures occlusion-based entity culling.
	 */
	private static void cave(List<String> cmds, Random random, int ox, int oy, int oz) {
		cmds.add("kill @e[type=!player]");
		cmds.add(f("fill %d %d %d %d %d %d minecraft:air", ox - 30, oy, oz + 7, ox + 30, oy + 8, oz + 45));
		cmds.add(f("fill %d %d %d %d %d %d minecraft:stone", ox - 30, oy - 1, oz + 7, ox + 30, oy - 1, oz + 45));
		cmds.add(f("fill %d %d %d %d %d %d minecraft:stone", ox - 40, oy - 10, oz + 5, ox + 40, oy + 40, oz + 6));
		String[] mobs = {"cow", "sheep", "pig", "zombie", "skeleton", "villager"};
		for (int i = 0; i < 300; i++) {
			int x = ox - 25 + (i % 20) * 2 + random.nextInt(2);
			int z = oz + 10 + (i / 20) * 2;
			cmds.add(f("summon minecraft:%s %d %d %d {NoAI:1b,Silent:1b,PersistenceRequired:1b}", mobs[i % mobs.length], x, oy, z));
		}
	}

	/**
	 * ImmediatelyFast-style load: a wall of signs with text, item frames, name-tagged mobs, and a
	 * full inventory (some enchanted) that the bench opens with -Dbasalt.bench.inventory=true.
	 */
	private static void ui(List<String> cmds, Random random, int ox, int oy, int oz) {
		cmds.add("kill @e[type=!player]");
		cmds.add(f("fill %d %d %d %d %d %d minecraft:air", ox - 12, oy, oz + 2, ox + 12, oy + 12, oz + 14));
		cmds.add(f("fill %d %d %d %d %d %d minecraft:stone", ox - 12, oy, oz + 15, ox + 12, oy + 12, oz + 15));
		String[] items = {"diamond_sword", "apple", "golden_apple", "bow", "map", "compass", "clock", "iron_pickaxe", "shield", "trident"};
		for (int x = -10; x <= 10; x++) {
			for (int y = 0; y < 10; y++) {
				if ((x + y) % 2 == 0) {
					cmds.add(f("setblock %d %d %d minecraft:oak_wall_sign[facing=north]{front_text:{messages:['Basalt %d','line two','line three','%d %d']}}", ox + x, oy + 1 + y, oz + 14, x * 10 + y, x, y));
				} else {
					cmds.add(f("summon minecraft:item_frame %d %d %d {Facing:2b,Item:{id:'minecraft:%s',count:1}}", ox + x, oy + 1 + y, oz + 14, items[random.nextInt(items.length)]));
				}
			}
		}
		for (int i = 0; i < 50; i++) {
			cmds.add(f("summon minecraft:%s %d %d %d {NoAI:1b,Silent:1b,CustomName:'Mob %d',CustomNameVisible:1b}", i % 2 == 0 ? "villager" : "zombie", ox - 10 + (i % 10) * 2, oy, oz + 5 + (i / 10) * 2, i));
		}
		cmds.add("clear @p");
		String[] inv = {"diamond_sword", "netherite_chestplate", "golden_apple", "enchanted_golden_apple", "bow", "map", "compass", "clock", "iron_pickaxe", "shield", "trident", "elytra", "cake", "redstone", "oak_log", "glass", "torch", "lantern"};
		for (int i = 0; i < 36; i++) {
			String id = inv[i % inv.length];
			cmds.add(i % 3 == 0 && !id.contains("apple") && !id.equals("redstone")
				? f("give @p minecraft:%s[enchantments={sharpness:3}] 1", id)
				: f("give @p minecraft:%s %d", id, 1 + i % 16));
		}
	}

	/** Storage hall: idle hopper-under-chest grid, looping hopper rings, piles of mergeable items. */
	private static void storage(List<String> cmds, Random random, int ox, int oy, int oz) {
		int y = oy + 1;
		// 16x16 grid of chests on top of idle hoppers feeding nothing
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				cmds.add(f("setblock %d %d %d minecraft:hopper[facing=down]", ox + 4 + x * 2, y, oz + 4 + z * 2));
				cmds.add(f("setblock %d %d %d minecraft:chest", ox + 4 + x * 2, y + 1, oz + 4 + z * 2));
			}
		}
		// 16 rings of 4x4 hoppers (12 hoppers each) circulating items forever
		for (int r = 0; r < 16; r++) {
			int bx = ox - 40 + (r % 4) * 6, bz = oz + 4 + (r / 4) * 6;
			for (int i = 0; i < 3; i++) {
				cmds.add(f("setblock %d %d %d minecraft:hopper[facing=east]", bx + i, y, bz));
				cmds.add(f("setblock %d %d %d minecraft:hopper[facing=south]", bx + 3, y, bz + i));
				cmds.add(f("setblock %d %d %d minecraft:hopper[facing=west]", bx + 3 - i, y, bz + 3));
				cmds.add(f("setblock %d %d %d minecraft:hopper[facing=north]", bx, y, bz + 3 - i));
			}
			cmds.add(f("item replace block %d %d %d container.0 with minecraft:iron_ingot 64", bx, y, bz));
		}
		// 400 identical items in 40 piles of 10 (merge candidates), never picked up
		for (int i = 0; i < 400; i++) {
			int pile = i / 10;
			cmds.add(f("summon minecraft:item %d %d %d {Item:{id:\"minecraft:cobblestone\",count:1},PickupDelay:32767}",
				ox - 20 + (pile % 8) * 3, oy + 1, oz - 20 - (pile / 8) * 3));
		}
	}

	private static void farm(List<String> cmds, Random random, int ox, int oy, int oz) {

		// animal pen: 24x24, glass walls, 300 mixed animals
		pen(cmds, ox + 6, oy, oz + 6, 24);
		String[] animals = {"cow", "sheep", "pig", "chicken"};
		for (int i = 0; i < 300; i++) {
			cmds.add(f("summon minecraft:%s %d %d %d", animals[i % animals.length], ox + 7 + random.nextInt(22), oy, oz + 7 + random.nextInt(22)));
		}
		// villager pen: 16x16, 60 villagers (heavy AI/brain)
		pen(cmds, ox - 22, oy, oz + 6, 16);
		for (int i = 0; i < 60; i++) {
			cmds.add(f("summon minecraft:villager %d %d %d", ox - 21 + random.nextInt(14), oy, oz - 0 + 7 + random.nextInt(14)));
		}
		// 8 hopper chains of 32, first hopper of each holds 5 stacks, ending in a chest
		for (int line = 0; line < 8; line++) {
			int z = oz - 10 - line * 2, y = oy + 1;
			cmds.add(f("fill %d %d %d %d %d %d minecraft:hopper[facing=east]", ox - 16, y, z, ox + 15, y, z));
			cmds.add(f("setblock %d %d %d minecraft:chest", ox + 16, y, z));
			for (int slot = 0; slot < 5; slot++) {
				cmds.add(f("item replace block %d %d %d container.%d with minecraft:cobblestone 64", ox - 16, y, z, slot));
			}
		}
		// 200 loose items that can't merge (different items) and won't despawn during the run
		String[] items = {"stick", "dirt", "sand", "gravel", "oak_log", "wheat_seeds", "bone", "string"};
		for (int i = 0; i < 200; i++) {
			cmds.add(f("summon minecraft:item %d %d %d {Item:{id:'minecraft:%s',count:1},PickupDelay:32767}",
				ox - 20 + random.nextInt(40), oy + 2, oz - 30 - random.nextInt(10), items[i % items.length]));
		}
	}

	private static void pen(List<String> cmds, int x, int y, int z, int size) {
		cmds.add(f("fill %d %d %d %d %d %d minecraft:stone", x, y - 1, z, x + size, y - 1, z + size));
		cmds.add(f("fill %d %d %d %d %d %d minecraft:glass", x, y, z, x + size, y + 2, z + size));
		cmds.add(f("fill %d %d %d %d %d %d minecraft:air", x + 1, y, z + 1, x + size - 1, y + 2, z + size - 1));
	}

	private static String f(String fmt, Object... args) {
		return String.format(Locale.ROOT, fmt, args);
	}
}
