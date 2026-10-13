package dev.basalt.client.gpuculling;

import com.mojang.renderpearl.api.commands.RenderPass;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * A RenderPass that forwards to a real pass which can be swapped mid-way: vanilla draws the whole
 * main scene in one pass, and GPU occlusion culling needs to end it after the opaque phase (compute
 * work can't run inside a render pass) and continue in a new pass on the same attachments.
 */
public final class SplittablePass implements InvocationHandler {
	private RenderPass current;
	private final Supplier<RenderPass> reopen;
	public final com.mojang.renderpearl.api.textures.GpuTextureView depth;

	private SplittablePass(RenderPass first, Supplier<RenderPass> reopen, com.mojang.renderpearl.api.textures.GpuTextureView depth) {
		this.current = first;
		this.reopen = reopen;
		this.depth = depth;
	}

	public static RenderPass wrap(RenderPass first, Supplier<RenderPass> reopen, com.mojang.renderpearl.api.textures.GpuTextureView depth) {
		return (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[] {RenderPass.class}, new SplittablePass(first, reopen, depth));
	}

	public static SplittablePass of(RenderPass pass) {
		return Proxy.isProxyClass(pass.getClass()) && Proxy.getInvocationHandler(pass) instanceof SplittablePass s ? s : null;
	}

	/** Ends the current real pass, runs {@code between} outside any pass, and continues in a fresh one. */
	public RenderPass split(Runnable between) {
		this.current.close();
		between.run();
		this.current = this.reopen.get();
		return this.current;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(this.current, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
