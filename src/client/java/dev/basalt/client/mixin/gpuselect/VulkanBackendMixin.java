package dev.basalt.client.mixin.gpuselect;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import java.nio.IntBuffer;
import java.util.Locale;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Config: gpu = part of the device name (e.g. "RTX", "Radeon"). Empty = vanilla choice
 * (prefers a discrete GPU). Every device the driver reports is logged once to help pick a name.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	@Invoker("checkDeviceSuitability")
	private static BackendCreationException basalt$checkSuitability(VkPhysicalDevice device, Set<FeatureSet> required, Set<FeatureSet> optional) {
		throw new AssertionError();
	}

	@WrapMethod(method = "findPhysicalDevice")
	private static VulkanPhysicalDevice basalt$pickConfiguredGpu(
		VulkanInstance instance, Set<FeatureSet> required, Set<FeatureSet> optional, Operation<VulkanPhysicalDevice> original
	) throws BackendCreationException {
		String wanted = BasaltConfig.get("gpu", "").trim().toLowerCase(Locale.ROOT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.callocInt(1);
			VK12.vkEnumeratePhysicalDevices(instance.vkInstance(), count, null);
			PointerBuffer handles = stack.callocPointer(count.get(0));
			VK12.vkEnumeratePhysicalDevices(instance.vkInstance(), count, handles);
			for (int i = 0; i < count.get(0); i++) {
				VkPhysicalDevice device = new VkPhysicalDevice(handles.get(i), instance.vkInstance());
				VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
				VK12.vkGetPhysicalDeviceProperties(device, props);
				String name = props.deviceNameString();
				if (!dev.basalt.client.screen.GpuList.NAMES.contains(name)) {
					dev.basalt.client.screen.GpuList.NAMES.add(name);
				}
				Basalt.LOGGER.info("Vulkan device {}: {} (type {})", i, name, props.deviceType());
				if (!wanted.isEmpty() && name.toLowerCase(Locale.ROOT).contains(wanted)) {
					BackendCreationException reason = basalt$checkSuitability(device, required, optional);
					if (reason == null) {
						Basalt.LOGGER.info("Using configured GPU: {}", name);
						return new VulkanPhysicalDevice(device);
					}
					Basalt.LOGGER.warn("Configured GPU {} is not suitable: {}", name, reason.getMessage());
				}
			}
		}
		if (!wanted.isEmpty()) {
			Basalt.LOGGER.warn("No suitable GPU matches gpu={}, using default selection", wanted);
		}
		return original.call(instance, required, optional);
	}
}
