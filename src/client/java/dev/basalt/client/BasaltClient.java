package dev.basalt.client;

import dev.basalt.BasaltConfig;
import net.fabricmc.api.ClientModInitializer;

public class BasaltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BasaltConfig.save();
		if (Boolean.getBoolean("basalt.statecache.verify")) {
			long checked = 0, bad = 0;
			for (net.minecraft.world.level.block.state.BlockState state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
				for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
					for (net.minecraft.world.level.block.SupportType t : net.minecraft.world.level.block.SupportType.values()) {
						checked++;
						if (state.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO, d, t)
							!= t.isSupporting(state, net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO, d)) {
							bad++;
						}
					}
				}
			}
			dev.basalt.Basalt.LOGGER.info("[statecache] verify: {} face checks, {} mismatches", checked, bad);
		}
		String rustLib = System.getProperty("basalt.rust.experiment");
		if (rustLib != null) {
			boolean ok;
			try {
				ok = dev.basalt.client.gpu.RustPerlinExperiment.run(rustLib);
			} catch (Throwable t) {
				dev.basalt.Basalt.LOGGER.error("[rust] experiment failed", t);
				ok = false;
			}
			System.exit(ok ? 0 : 3);
		}
		if (Boolean.getBoolean("basalt.gpu.selftest")) {
			boolean ok = dev.basalt.client.gpu.PerlinGpuSelfTest.run(Integer.getInteger("basalt.gpu.selftest.trials", 400));
			System.exit(ok ? 0 : 3);
		}
	}
}
