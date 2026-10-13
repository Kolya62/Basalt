package dev.basalt.client.mixin.gpuculling;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccess {
	@Invoker("commandBuffer")
	VkCommandBuffer basalt$commandBuffer();
}
