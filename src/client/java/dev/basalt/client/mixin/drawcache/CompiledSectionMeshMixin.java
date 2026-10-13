package dev.basalt.client.mixin.drawcache;

import dev.basalt.client.drawcache.DrawCache;
import dev.basalt.client.drawcache.DrawCacheHolder;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public class CompiledSectionMeshMixin implements DrawCacheHolder {
	@Unique
	private volatile DrawCache basalt$drawCache;

	@Override
	public DrawCache basalt$drawCache() {
		return this.basalt$drawCache;
	}

	@Override
	public void basalt$setDrawCache(DrawCache cache) {
		this.basalt$drawCache = cache;
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void basalt$dropDrawCache(CallbackInfo ci) {
		this.basalt$drawCache = null;
	}
}
