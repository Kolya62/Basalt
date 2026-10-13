package dev.basalt;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Each feature lives in its own sub-package of {@code dev.basalt.mixin} (common) or
 * {@code dev.basalt.client.mixin} (client); the sub-package name is the feature's config key,
 * so every feature can be switched off independently.
 */
public class BasaltMixinPlugin implements IMixinConfigPlugin {

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String pkg = mixinClassName.substring(0, mixinClassName.lastIndexOf('.'));
		String feature = pkg.substring(pkg.lastIndexOf('.') + 1);
		boolean on = feature.equals("bench") || feature.equals("genbench")
			? System.getProperty("basalt." + feature) != null
			: !"false".equalsIgnoreCase(BasaltConfig.get(feature, "true").trim()); // e.g. "auto" keeps mixins in
		if (!on) {
			Basalt.LOGGER.info("Feature '{}' disabled, skipping {}", feature, mixinClassName);
		}
		return on;
	}

	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
	@Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
