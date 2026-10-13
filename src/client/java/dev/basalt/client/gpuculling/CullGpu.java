package dev.basalt.client.gpuculling;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

/** Raw Vulkan objects for Hi-Z occlusion culling, created on the game's own VkDevice. */
final class CullGpu {
	static final int SLOTS = 3;
	static final int MAX_SECTIONS = 1 << 17;
	private static final long SECTION_BYTES = 16L * MAX_SECTIONS, VISIBLE_BYTES = 4L * MAX_SECTIONS;

	final VulkanDevice device;
	final VkDevice vk;
	final long sampler;
	final Pipe hizInit, hizDown, cull, patch;
	/** Per slot: section origins (CPU writes, GPU reads; write-combined memory is fine). */
	final long[] sectionBuffer = new long[SLOTS], sectionMemory = new long[SLOTS], sectionPtr = new long[SLOTS];
	/**
	 * Per slot: one uint per section (GPU writes, CPU reads it back). Kept in HOST_CACHED memory when
	 * the device has it: the first HOST_VISIBLE|HOST_COHERENT type is usually write-combined (or BAR)
	 * memory, where every CPU load is an uncached bus transaction.
	 */
	final long[] visibleBuffer = new long[SLOTS], visibleMemory = new long[SLOTS], visiblePtr = new long[SLOTS];
	final boolean[] visibleCoherent = new boolean[SLOTS];
	final long[] slotPool = new long[SLOTS];

	// Hi-Z pyramid; recreated when the depth buffer size changes
	long depthImage, depthView, hizImage, hizMemory, hizAllView;
	long[] hizMipView = new long[0];
	int depthW, depthH, hizW, hizH, levels;
	boolean hizInitialised;

	record Pipe(long setLayout, long layout, long pipeline) {
	}

	CullGpu(VulkanDevice device) {
		this.device = device;
		this.vk = device.vkDevice();
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateSampler(this.vk, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(VK_LOD_CLAMP_NONE), null, lp), "sampler");
			this.sampler = lp.get(0);
		}
		this.hizInit = this.pipe("hiz_init.comp", 16, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		this.hizDown = this.pipe("hiz_down.comp", 16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
		this.cull = this.pipe("cull.comp", 80, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
		this.patch = this.pipe("patch.comp", 8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
		for (int s = 0; s < SLOTS; s++) {
			long[] sections = this.hostBuffer(SECTION_BYTES, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
			this.sectionBuffer[s] = sections[0];
			this.sectionMemory[s] = sections[1];
			this.sectionPtr[s] = sections[2];
			long[] visible = this.hostBuffer(VISIBLE_BYTES, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			this.visibleBuffer[s] = visible[0];
			this.visibleMemory[s] = visible[1];
			this.visiblePtr[s] = visible[2];
			this.visibleCoherent[s] = visible[3] != 0;
			this.slotPool[s] = this.pool(64, 256);
		}
	}

	long sectionsAddress(int slot) {
		return this.sectionPtr[slot];
	}

	/** Address of the slot's visibility results, made visible to the host (invalidated if not coherent). */
	long visibleAddress(int slot) {
		if (!this.visibleCoherent[slot]) {
			try (MemoryStack stack = stackPush()) {
				check(vkInvalidateMappedMemoryRanges(this.vk, VkMappedMemoryRange.calloc(1, stack).sType$Default().memory(this.visibleMemory[slot])
					.offset(0).size(VK_WHOLE_SIZE)), "invalidate");
			}
		}
		return this.visiblePtr[slot];
	}

	/** (Re)creates the Hi-Z pyramid for a depth buffer of the given image/size/format. */
	void ensureHiz(long image, int width, int height, int vkFormat) {
		if (image == this.depthImage && width == this.depthW && height == this.depthH) {
			return;
		}
		vkDeviceWaitIdle(this.vk); // rare (resize); old views/images may still be in flight
		this.destroyHiz();
		this.depthImage = image;
		this.depthW = width;
		this.depthH = height;
		this.hizW = Math.max(1, (width + 1) / 2);
		this.hizH = Math.max(1, (height + 1) / 2);
		// stop around 16 px: coarser levels add barriers but no useful precision
		this.levels = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(this.hizW, this.hizH)) - 4);
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateImageView(this.vk, VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(vkFormat)
				.subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1)), null, lp), "depth view");
			this.depthView = lp.get(0);
			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R32_SFLOAT)
				.mipLevels(this.levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			ici.extent().set(this.hizW, this.hizH, 1);
			check(vkCreateImage(this.vk, ici, null, lp), "hiz image");
			this.hizImage = lp.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetImageMemoryRequirements(this.vk, this.hizImage, req);
			check(vkAllocateMemory(this.vk, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
				.memoryTypeIndex(this.memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)), null, lp), "hiz memory");
			this.hizMemory = lp.get(0);
			vkBindImageMemory(this.vk, this.hizImage, this.hizMemory, 0);
			check(vkCreateImageView(this.vk, VkImageViewCreateInfo.calloc(stack).sType$Default().image(this.hizImage).viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(VK_FORMAT_R32_SFLOAT).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(this.levels).layerCount(1)), null, lp), "hiz view");
			this.hizAllView = lp.get(0);
			this.hizMipView = new long[this.levels];
			for (int l = 0; l < this.levels; l++) {
				int level = l;
				check(vkCreateImageView(this.vk, VkImageViewCreateInfo.calloc(stack).sType$Default().image(this.hizImage).viewType(VK_IMAGE_VIEW_TYPE_2D)
					.format(VK_FORMAT_R32_SFLOAT).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(level).levelCount(1).layerCount(1)), null, lp), "hiz mip");
				this.hizMipView[l] = lp.get(0);
			}
		}
		this.hizInitialised = false;
	}

	private void destroyHiz() {
		for (long v : this.hizMipView) {
			vkDestroyImageView(this.vk, v, null);
		}
		this.hizMipView = new long[0];
		if (this.hizAllView != 0) {
			vkDestroyImageView(this.vk, this.hizAllView, null);
			vkDestroyImage(this.vk, this.hizImage, null);
			vkFreeMemory(this.vk, this.hizMemory, null);
			this.hizAllView = 0;
		}
		if (this.depthView != 0) {
			vkDestroyImageView(this.vk, this.depthView, null);
			this.depthView = 0;
		}
	}

	long allocSet(int slot, Pipe pipe) {
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			check(vkAllocateDescriptorSets(this.vk, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(this.slotPool[slot])
				.pSetLayouts(stack.longs(pipe.setLayout())), lp), "descriptor set");
			return lp.get(0);
		}
	}

	void resetSlot(int slot) {
		vkResetDescriptorPool(this.vk, this.slotPool[slot], 0);
	}

	void writeImage(long set, int binding, long view, boolean sampled) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
				.sampler(sampled ? this.sampler : VK_NULL_HANDLE);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
				.descriptorType(sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
			vkUpdateDescriptorSets(this.vk, w, null);
		}
	}

	void writeBuffer(long set, int binding, long buffer, long offset, long range) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(offset).range(range);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
			vkUpdateDescriptorSets(this.vk, w, null);
		}
	}

	long sectionBufferHandle(int slot) {
		return this.sectionBuffer[slot];
	}

	long visibleBufferHandle(int slot) {
		return this.visibleBuffer[slot];
	}

	private long pool(int sets, int descriptors) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(descriptors);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(descriptors);
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(descriptors);
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateDescriptorPool(this.vk, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(sets).pPoolSizes(sizes), null, lp), "pool");
			return lp.get(0);
		}
	}

	/** Returns {buffer, memory, mapped pointer, 1 if the memory is host-coherent else 0}. */
	private long[] hostBuffer(long size, int required, int preferred) {
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateBuffer(this.vk, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "buffer");
			long buffer = lp.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(this.vk, buffer, req);
			int type = this.memoryType(req.memoryTypeBits(), required, preferred);
			check(vkAllocateMemory(this.vk, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
				.memoryTypeIndex(type), null, lp), "memory");
			long memory = lp.get(0);
			vkBindBufferMemory(this.vk, buffer, memory, 0);
			PointerBuffer pp = stack.mallocPointer(1);
			check(vkMapMemory(this.vk, memory, 0, VK_WHOLE_SIZE, 0, pp), "map");
			int flags = this.memoryFlags(type);
			dev.basalt.Basalt.LOGGER.debug("[gpuculling] {} bytes in memory type {} (flags 0x{})", size, type, Integer.toHexString(flags));
			return new long[] {buffer, memory, pp.get(0), (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0 ? 1 : 0};
		}
	}

	/**
	 * The type with all {@code required} flags and the best {@code preferred} ones, a higher flag bit
	 * counting more (so HOST_CACHED beats HOST_COHERENT); the first type wins ties.
	 */
	private int memoryType(int bits, int required, int preferred) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(this.vk.getPhysicalDevice(), mp);
			int best = -1, bestScore = -1;
			for (int i = 0; i < mp.memoryTypeCount(); i++) {
				int flags = mp.memoryTypes(i).propertyFlags();
				if ((bits & 1 << i) != 0 && (flags & required) == required) {
					int score = flags & preferred;
					if (score > bestScore) {
						best = i;
						bestScore = score;
					}
				}
			}
			if (best >= 0) {
				return best;
			}
		}
		throw new IllegalStateException("no memory type for flags " + required);
	}

	private int memoryFlags(int type) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(this.vk.getPhysicalDevice(), mp);
			return mp.memoryTypes(type).propertyFlags();
		}
	}

	private Pipe pipe(String shader, int pushBytes, int... bindings) {
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
			for (int i = 0; i < bindings.length; i++) {
				b.get(i).binding(i).descriptorType(bindings[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			check(vkCreateDescriptorSetLayout(this.vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, lp), "dsl");
			long setLayout = lp.get(0);
			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(pushBytes);
			check(vkCreatePipelineLayout(this.vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout))
				.pPushConstantRanges(pc), null, lp), "pipeline layout");
			long layout = lp.get(0);
			ByteBuffer spirv = compile(shader);
			check(vkCreateShaderModule(this.vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, lp), "module");
			long module = lp.get(0);
			MemoryUtil.memFree(spirv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
			check(vkCreateComputePipelines(this.vk, VK_NULL_HANDLE, ci, null, lp), "pipeline " + shader);
			vkDestroyShaderModule(this.vk, module, null);
			return new Pipe(setLayout, layout, lp.get(0));
		}
	}

	private static ByteBuffer compile(String name) {
		String source;
		try (InputStream in = CullGpu.class.getResourceAsStream("/assets/basalt/shaders/compute/" + name)) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader, name, "main", options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
				copy.put(spv).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	static void check(int r, String what) {
		if (r != VK_SUCCESS) {
			throw new IllegalStateException("Vulkan " + what + " failed: " + r);
		}
	}
}
