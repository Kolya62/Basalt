package dev.basalt.client.mixin.gpuculling;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccess {
	@Accessor("backend")
	GpuDeviceBackend basalt$backend();
}
