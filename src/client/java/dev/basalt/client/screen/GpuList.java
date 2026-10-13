package dev.basalt.client.screen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Vulkan device names seen at startup, offered by the settings screen. */
public final class GpuList {
	public static final List<String> NAMES = new CopyOnWriteArrayList<>();

	private GpuList() {
	}
}
