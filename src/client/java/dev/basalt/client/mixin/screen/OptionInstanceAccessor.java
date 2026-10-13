package dev.basalt.client.mixin.screen;

import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
	@Accessor("caption")
	Component basalt$caption();

	@Accessor("toString")
	Function<T, Component> basalt$toString();

	@Accessor("tooltip")
	OptionInstance.TooltipSupplier<T> basalt$tooltip();
}
