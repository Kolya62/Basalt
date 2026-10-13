package dev.basalt.client.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

/**
 * Minimal headless Vulkan compute context, separate from the game's renderer, for bit-exact
 * world-generation kernels. Requires fp64 and RTE rounding + signed-zero preservation for fp32/64.
 */
public final class VkCompute implements AutoCloseable {
	public final VkInstance instance;
	public final VkPhysicalDevice physicalDevice;
	public final VkDevice device;
	public final VkQueue queue;
	public final int queueFamily;
	public final String deviceName;
	public final boolean denormPreserve;
	private final long commandPool;

	private VkCompute(VkInstance instance, VkPhysicalDevice pd, VkDevice device, VkQueue queue, int family, String name, boolean denorm, long pool) {
		this.instance = instance;
		this.physicalDevice = pd;
		this.device = device;
		this.queue = queue;
		this.queueFamily = family;
		this.deviceName = name;
		this.denormPreserve = denorm;
		this.commandPool = pool;
	}

	/** Returns null (and logs why) if no device can run exact kernels. */
	public static VkCompute create() {
		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Basalt")).apiVersion(VK_API_VERSION_1_2);
			VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
			PointerBuffer pp = stack.mallocPointer(1);
			check(vkCreateInstance(ici, null, pp), "vkCreateInstance");
			VkInstance instance = new VkInstance(pp.get(0), ici);

			IntBuffer n = stack.mallocInt(1);
			vkEnumeratePhysicalDevices(instance, n, null);
			PointerBuffer devs = stack.mallocPointer(n.get(0));
			vkEnumeratePhysicalDevices(instance, n, devs);
			String wanted = BasaltConfig.get("gpu", "").toLowerCase(Locale.ROOT);
			VkPhysicalDevice best = null;
			int bestScore = -1;
			boolean bestDenorm = false;
			String bestName = null;
			for (int i = 0; i < n.get(0); i++) {
				VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
				VkPhysicalDeviceVulkan12Properties p12 = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default();
				VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(p12);
				vkGetPhysicalDeviceProperties2(pd, props);
				VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.calloc(stack);
				vkGetPhysicalDeviceFeatures(pd, feats);
				String name = props.properties().deviceNameString();
				boolean exact = feats.shaderFloat64() && p12.shaderRoundingModeRTEFloat32() && p12.shaderRoundingModeRTEFloat64()
					&& p12.shaderSignedZeroInfNanPreserveFloat32() && p12.shaderSignedZeroInfNanPreserveFloat64();
				if (!exact) {
					Basalt.LOGGER.info("[gpu] {} lacks fp64/RTE/signed-zero support, skipped", name);
					continue;
				}
				int score = (props.properties().deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 10 : 1)
					+ (!wanted.isEmpty() && name.toLowerCase(Locale.ROOT).contains(wanted) ? 100 : 0);
				if (score > bestScore) {
					bestScore = score;
					best = pd;
					bestName = name;
					bestDenorm = p12.shaderDenormPreserveFloat32() && p12.shaderDenormPreserveFloat64();
				}
			}
			if (best == null) {
				vkDestroyInstance(instance, null);
				return null;
			}

			vkGetPhysicalDeviceQueueFamilyProperties(best, n, null);
			VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(best, n, qf);
			int family = -1;
			for (int i = 0; i < qf.capacity(); i++) {
				if ((qf.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
					family = i;
					// prefer a compute-only queue so we don't compete with the renderer's graphics queue
					if ((qf.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) {
						break;
					}
				}
			}
			VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1f));
			VkPhysicalDeviceFeatures enable = VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(true);
			VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qci).pEnabledFeatures(enable);
			check(vkCreateDevice(best, dci, null, pp), "vkCreateDevice");
			VkDevice device = new VkDevice(pp.get(0), best, dci);
			vkGetDeviceQueue(device, family, 0, pp);
			VkQueue queue = new VkQueue(pp.get(0), device);
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family)
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, lp), "vkCreateCommandPool");
			Basalt.LOGGER.info("[gpu] compute device: {} (queue family {}, denorm preserve {})", bestName, family, bestDenorm);
			return new VkCompute(instance, best, device, queue, family, bestName, bestDenorm, lp.get(0));
		} catch (Throwable t) {
			Basalt.LOGGER.warn("[gpu] Vulkan compute unavailable", t);
			return null;
		}
	}

	/** Compiles GLSL to SPIR-V with shaderc (Vulkan 1.2 / SPIR-V 1.5, float controls are core). */
	public ByteBuffer compile(String name, String source) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			if (this.denormPreserve) {
				Shaderc.shaderc_compile_options_add_macro_definition(options, "DENORM_PRESERVE", "1");
			}
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader, name, "main", options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("Shader " + name + " failed: " + Shaderc.shaderc_result_get_error_message(result));
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

	/** Host-visible, coherent storage buffer (prototype; a real pipeline would use device-local + staging). */
	public Buffer buffer(long size) {
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			check(vkCreateBuffer(this.device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "vkCreateBuffer");
			long buffer = lp.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(this.device, buffer, req);
			VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, mp);
			int want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
			int type = -1;
			for (int i = 0; i < mp.memoryTypeCount(); i++) {
				if ((req.memoryTypeBits() & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & want) == want) {
					type = i;
					break;
				}
			}
			check(vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size()).memoryTypeIndex(type), null, lp), "vkAllocateMemory");
			long memory = lp.get(0);
			vkBindBufferMemory(this.device, buffer, memory, 0);
			PointerBuffer ptr = stack.mallocPointer(1);
			vkMapMemory(this.device, memory, 0, size, 0, ptr);
			return new Buffer(buffer, memory, MemoryUtil.memByteBuffer(ptr.get(0), (int) size));
		}
	}

	public record Buffer(long handle, long memory, ByteBuffer mapped) {
	}

	/** A compute pipeline whose bindings 0..n-1 are storage buffers. */
	public final class Kernel implements AutoCloseable {
		private final long setLayout, layout, pipeline, descriptorPool, set;

		public Kernel(ByteBuffer spirv, Buffer... buffers) {
			try (MemoryStack stack = stackPush()) {
				LongBuffer lp = stack.mallocLong(1);
				VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(buffers.length, stack);
				for (int i = 0; i < buffers.length; i++) {
					b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
				}
				check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, lp), "dsl");
				this.setLayout = lp.get(0);
				check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(this.setLayout)), null, lp), "pl");
				this.layout = lp.get(0);
				check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, lp), "module");
				long module = lp.get(0);
				VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(this.layout);
				cpi.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
				check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpi, null, lp), "pipeline");
				this.pipeline = lp.get(0);
				vkDestroyShaderModule(device, module, null);
				VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(buffers.length);
				check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(ps), null, lp), "pool");
				this.descriptorPool = lp.get(0);
				check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(this.descriptorPool)
					.pSetLayouts(stack.longs(this.setLayout)), lp), "set");
				this.set = lp.get(0);
				VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(buffers.length, stack);
				for (int i = 0; i < buffers.length; i++) {
					w.get(i).sType$Default().dstSet(this.set).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
						.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffers[i].handle()).offset(0).range(VK_WHOLE_SIZE));
				}
				vkUpdateDescriptorSets(device, w, null);
			}
		}

		/** Records, submits and waits for one dispatch. */
		public void dispatch(int groups) {
			try (MemoryStack stack = stackPush()) {
				PointerBuffer pp = stack.mallocPointer(1);
				vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool)
					.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp);
				VkCommandBuffer cb = new VkCommandBuffer(pp.get(0), device);
				vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
				vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
				vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, this.layout, 0, stack.longs(this.set), null);
				vkCmdDispatch(cb, groups, 1, 1);
				vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
					VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT), null, null);
				vkEndCommandBuffer(cb);
				LongBuffer lp = stack.mallocLong(1);
				vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp);
				check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb)), lp.get(0)), "submit");
				vkWaitForFences(device, lp, true, Long.MAX_VALUE);
				vkDestroyFence(device, lp.get(0), null);
				vkFreeCommandBuffers(device, commandPool, cb);
			}
		}

		@Override
		public void close() {
			vkDestroyPipeline(device, this.pipeline, null);
			vkDestroyPipelineLayout(device, this.layout, null);
			vkDestroyDescriptorPool(device, this.descriptorPool, null);
			vkDestroyDescriptorSetLayout(device, this.setLayout, null);
		}
	}

	public void free(Buffer b) {
		vkUnmapMemory(this.device, b.memory());
		vkDestroyBuffer(this.device, b.handle(), null);
		vkFreeMemory(this.device, b.memory(), null);
	}

	@Override
	public void close() {
		vkDestroyCommandPool(this.device, this.commandPool, null);
		vkDestroyDevice(this.device, null);
		vkDestroyInstance(this.instance, null);
	}

	static void check(int result, String what) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(what + " failed: " + result);
		}
	}
}
