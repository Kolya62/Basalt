package dev.basalt.client.mixin.screen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.basalt.client.screen.BasaltOptionsScreen;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Like Sodium: Options -> Video Settings opens Basalt's screen instead of the vanilla list. */
@Mixin(OptionsScreen.class)
public class VideoSettingsScreenMixin {
	@WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"))
	private Button basalt$replace(OptionsScreen self, Component message, Supplier<Screen> screen, Operation<Button> original) {
		if (message.getContents() instanceof TranslatableContents t && t.getKey().equals("options.video")) {
			return original.call(self, message, (Supplier<Screen>) () -> new BasaltOptionsScreen(self));
		}
		return original.call(self, message, screen);
	}
}
