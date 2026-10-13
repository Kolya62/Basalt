package dev.basalt.client.drawcache;

import org.jspecify.annotations.Nullable;

public interface DrawCacheHolder {
	@Nullable DrawCache basalt$drawCache();

	void basalt$setDrawCache(@Nullable DrawCache cache);
}
