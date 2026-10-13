package dev.basalt.client.mixin.swapchain;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla throws (and logs a stack trace every frame) when the window size briefly falls outside
 * the surface's allowed extent during a resize. Clamp the request into the allowed range instead;
 * the surface stays valid and the next resize event corrects the size.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private long surface;

	@ModifyVariable(method = "configure", at = @At("HEAD"), argsOnly = true)
	private GpuSurface.Configuration basalt$clampExtent(GpuSurface.Configuration config) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
			if (KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.device.vkDevice().getPhysicalDevice(), this.surface, caps) != 0) {
				return config;
			}
			int maxW = caps.maxImageExtent().width(), maxH = caps.maxImageExtent().height();
			if (maxW == 0 || maxH == 0) {
				return config; // minimized: let vanilla report it and retry later
			}
			int w = Math.clamp(config.width(), caps.minImageExtent().width(), maxW);
			int h = Math.clamp(config.height(), caps.minImageExtent().height(), maxH);
			return w == config.width() && h == config.height() ? config : new GpuSurface.Configuration(w, h, config.presentMode());
		}
	}
}
