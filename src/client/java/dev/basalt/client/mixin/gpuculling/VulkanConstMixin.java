package dev.basalt.client.mixin.gpuculling;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Indirect-command buffers also get STORAGE usage so the culling shader can patch them in place. */
@Mixin(VulkanConst.class)
public class VulkanConstMixin {
	@ModifyReturnValue(method = "bufferUsageToVk", at = @At("RETURN"))
	private static int basalt$storageForIndirect(int vkUsage, int usage) {
		return (usage & 512) != 0 ? vkUsage | 32 /* VK_BUFFER_USAGE_STORAGE_BUFFER_BIT */ : vkUsage;
	}
}
