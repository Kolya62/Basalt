package dev.basalt.client.mixin.forcevulkan;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Basalt targets the Vulkan renderer only, so the game is switched to Vulkan on startup.
 * Safe by construction: vanilla's VULKAN setting still falls back to OpenGL if Vulkan cannot be
 * created, and if a startup on Vulkan did not finish (crash/hang), forcing is turned off for good
 * (force_vulkan=false) and vanilla's own crash recovery is left alone.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow @Final public Options options;

	/** First read: vanilla's "last startup crashed while on Vulkan" check. */
	@ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
	private Object basalt$crashCheck(Object value, @Local(name = "lastStartWasClean") boolean clean) {
		if (!clean && value == PreferredGraphicsApi.VULKAN && !"false".equals(BasaltConfig.stored("force_vulkan"))) {
			Basalt.LOGGER.warn("Last startup on Vulkan did not finish; Basalt stops forcing Vulkan (force_vulkan=false)");
			BasaltConfig.set("force_vulkan", "false");
			BasaltConfig.save();
		}
		return value;
	}

	/** The read that decides the backend: switch to Vulkan unless forcing was disabled. */
	@ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 3))
	private Object basalt$forceVulkan(Object value, @Local(name = "lastStartWasClean") boolean clean) {
		if (clean && value != PreferredGraphicsApi.VULKAN && !"false".equals(BasaltConfig.stored("force_vulkan"))) {
			Basalt.LOGGER.info("Basalt: switching graphics API {} -> Vulkan", value);
			this.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
			this.options.save();
			return PreferredGraphicsApi.VULKAN;
		}
		return value;
	}
}
