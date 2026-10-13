package dev.basalt.client.screen;

import dev.basalt.BasaltConfig;
import dev.basalt.client.mixin.screen.OptionInstanceAccessor;
import dev.basalt.client.mixin.screen.TooltipAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Video settings in the style of Sodium: pages on the left, option rows in the middle, the
 * hovered option's description on the right, and Undo / Apply / Done. Changes are kept pending
 * until applied. Basalt's own options are mixins chosen at launch and need a restart.
 */
public class BasaltOptionsScreen extends Screen {
	private static final int ACCENT = 0xFF94E4D3;
	private static final int ROW_H = 18;
	private static final int GROUP_GAP = 4;
	private static final int SIDEBAR_W = 130;
	private static final int OPTIONS_W = 300;

	private final Screen parent;
	private final List<Page> pages = new ArrayList<>();
	private int page;
	private double scroll;
	private @Nullable Opt dragging;
	private @Nullable Opt hovered;
	/** Self-test only: pretend the mouse is here when drawing. */
	public int @Nullable [] fakeMouse;

	public BasaltOptionsScreen(Screen parent) {
		super(Component.translatable("options.videoTitle"));
		this.parent = parent;
		Options o = Minecraft.getInstance().options;
		this.pages.add(new Page("general",
			List.of(van(o.renderDistance()), van(o.simulationDistance()), van(o.gamma())),
			List.of(van(o.guiScale()), van(o.fullscreen()), van(o.enableVsync()), van(o.framerateLimit())),
			List.of(van(o.bobView()), van(o.attackIndicator()), van(o.showAutosaveIndicator()))));
		this.pages.add(new Page("quality",
			List.of(van(o.cloudStatus()), van(o.cloudRange()), van(o.weatherRadius()), van(o.cutoutLeaves()), van(o.particles())),
			List.of(van(o.ambientOcclusion()), van(o.biomeBlendRadius())),
			List.of(van(o.entityDistanceScaling()), van(o.entityShadows()), van(o.vignette())),
			List.of(van(o.mipmapLevels()), van(o.textureFiltering()), van(o.maxAnisotropyBit()), van(o.improvedTransparency()), van(o.chunkSectionFadeInTime()))));
		this.pages.add(new Page("performance",
			List.of(van(o.prioritizeChunkUpdates()), van(o.inactivityFpsLimit())),
			List.of(onOff("compactvertex"), onOff("facecull"), onOff("drawcache"), new BasaltOpt("gpuculling", List.of("auto", "true", "false"))),
			List.of(onOff("modelpart"), onOff("textcache"), onOff("tint"), onOff("entityculling"), new BasaltOpt("entityculling.max_distance", List.of("64", "96", "128", "192", "256")))));
		List<String> gpus = new ArrayList<>();
		gpus.add("");
		gpus.addAll(GpuList.NAMES);
		this.pages.add(new Page("advanced",
			List.of(van(o.preferredGraphicsBackend())),
			List.of(onOff("presentmode"), new BasaltOpt("vsync_mode", List.of("mailbox", "fifo")), onOff("swapchain")),
			List.of(onOff("gpuselect"), new BasaltOpt("gpu", gpus))));
		this.pages.add(new Page("world",
			List.of(onOff("parallelgen"), onOff("nativenoise")),
			List.of(onOff("collisions"), onOff("poi"), onOff("entitysections"), onOff("brain"), onOff("randomtick"), onOff("chunklevel")),
			List.of(onOff("fastmap"), onOff("statecache"), onOff("threaddetect"))));
	}

	// ---------------------------------------------------------------- options

	public abstract static class Opt {
		public abstract Component name();

		public abstract @Nullable Component description();

		public abstract Component valueText();

		public boolean slider() {
			return false;
		}

		public double sliderValue() {
			return 0;
		}

		void setSlider(double v) {
		}

		abstract void cycle(boolean back);

		abstract boolean dirty();

		abstract void apply();

		abstract void undo();

		boolean needsRestart() {
			return false;
		}
	}

	private static <T> VanillaOpt<T> van(OptionInstance<T> instance) {
		return new VanillaOpt<>(instance);
	}

	static final class VanillaOpt<T> extends Opt {
		private final OptionInstance<T> instance;
		private T pending;

		VanillaOpt(OptionInstance<T> instance) {
			this.instance = instance;
			this.pending = instance.get();
		}

		@SuppressWarnings("unchecked")
		private OptionInstanceAccessor<T> acc() {
			return (OptionInstanceAccessor<T>) (Object) this.instance;
		}

		@Override
		public Component name() {
			return this.acc().basalt$caption();
		}

		@Override
		public @Nullable Component description() {
			Tooltip tip = this.acc().basalt$tooltip().apply(this.pending);
			return tip == null ? null : ((TooltipAccessor) tip).basalt$message();
		}

		@Override
		public Component valueText() {
			Component full = this.acc().basalt$toString().apply(this.pending);
			String prefix = this.name().getString() + ": ";
			String s = full.getString();
			return s.startsWith(prefix) ? Component.literal(s.substring(prefix.length())) : full;
		}

		@Override
		public boolean slider() {
			return this.instance.values() instanceof OptionInstance.SliderableValueSet<T>;
		}

		@Override
		public double sliderValue() {
			return ((OptionInstance.SliderableValueSet<T>) this.instance.values()).toSliderValue(this.pending);
		}

		@Override
		void setSlider(double v) {
			this.pending = ((OptionInstance.SliderableValueSet<T>) this.instance.values()).fromSliderValue(Mth.clamp(v, 0, 1));
		}

		@Override
		void cycle(boolean back) {
			if (this.instance.values() instanceof OptionInstance.CycleableValueSet<T> c) {
				List<T> list = c.valueListSupplier().getSelectedList();
				int i = list.indexOf(this.pending);
				this.pending = list.get(Math.floorMod(i + (back ? -1 : 1), list.size()));
			}
		}

		@Override
		boolean dirty() {
			return !Objects.equals(this.pending, this.instance.get());
		}

		@Override
		void apply() {
			if (this.dirty()) {
				this.instance.set(this.pending);
			}
		}

		@Override
		void undo() {
			this.pending = this.instance.get();
		}
	}

	private static BasaltOpt onOff(String key) {
		return new BasaltOpt(key, List.of("true", "false"));
	}

	static final class BasaltOpt extends Opt {
		private static final java.util.Map<String, String> AT_LAUNCH = new java.util.HashMap<>();
		private final String key;
		private final List<String> values;
		private String pending;

		BasaltOpt(String key, List<String> values) {
			this.key = key;
			this.values = new ArrayList<>(values);
			this.pending = normalize(BasaltConfig.stored(key));
			AT_LAUNCH.putIfAbsent(key, this.pending);
			if (!this.values.contains(this.pending)) {
				this.values.add(this.pending);
			}
		}

		private String normalize(String v) {
			v = v.trim();
			return this.values.contains("true") && !"false".equalsIgnoreCase(v) && !this.values.contains(v) ? "true" : v;
		}

		@Override
		public Component name() {
			return Component.translatable("basalt.option." + this.key);
		}

		@Override
		public Component description() {
			return Component.translatable("basalt.option." + this.key + ".tooltip");
		}

		@Override
		public Component valueText() {
			if (this.pending.equals("true")) {
				return CommonComponents.OPTION_ON;
			}
			if (this.pending.equals("false")) {
				return CommonComponents.OPTION_OFF;
			}
			if (this.pending.isEmpty()) {
				return Component.translatable("basalt.value.auto");
			}
			String tr = "basalt.value." + this.key + "." + this.pending;
			return Language.getInstance().has(tr) ? Component.translatable(tr) : Component.literal(this.pending);
		}

		@Override
		void cycle(boolean back) {
			int i = this.values.indexOf(this.pending);
			this.pending = this.values.get(Math.floorMod(i + (back ? -1 : 1), this.values.size()));
		}

		@Override
		boolean dirty() {
			return !this.pending.equals(normalize(BasaltConfig.stored(this.key)));
		}

		@Override
		void apply() {
			if (this.dirty()) {
				BasaltConfig.set(this.key, this.pending);
			}
		}

		@Override
		void undo() {
			this.pending = normalize(BasaltConfig.stored(this.key));
		}

		@Override
		boolean needsRestart() {
			return true;
		}

		boolean differsFromLaunch() {
			return !this.pending.equals(AT_LAUNCH.get(this.key));
		}
	}

	record Page(String id, List<List<Opt>> groups) {
		@SafeVarargs
		Page(String id, List<? extends Opt>... groups) {
			this(id, java.util.Arrays.stream(groups).map(g -> List.<Opt>copyOf(g)).toList());
		}

		Component title() {
			return Component.translatable("basalt.page." + this.id);
		}
	}

	private List<Opt> allOptions() {
		List<Opt> all = new ArrayList<>();
		for (Page p : this.pages) {
			p.groups().forEach(all::addAll);
		}
		return all;
	}

	private boolean anyDirty() {
		return this.allOptions().stream().anyMatch(Opt::dirty);
	}

	private void applyAll() {
		boolean basalt = false;
		for (Opt opt : this.allOptions()) {
			basalt |= opt instanceof BasaltOpt && opt.dirty();
			opt.apply();
		}
		this.minecraft.options.save();
		if (basalt) {
			BasaltConfig.save();
		}
	}

	private boolean restartPending() {
		return this.allOptions().stream().anyMatch(o -> o instanceof BasaltOpt b && b.differsFromLaunch());
	}

	// ---------------------------------------------------------------- layout

	private int optionsX() {
		return SIDEBAR_W + 12;
	}

	private int optionsW() {
		return Math.min(OPTIONS_W, this.width - SIDEBAR_W - 24);
	}

	private int contentTop() {
		return 12;
	}

	private int contentBottom() {
		return this.height - 34;
	}

	private int contentHeight() {
		int h = 0;
		for (List<Opt> g : this.pages.get(this.page).groups()) {
			h += g.size() * ROW_H + GROUP_GAP;
		}
		return h;
	}

	private void clampScroll() {
		int max = Math.max(0, this.contentHeight() - (this.contentBottom() - this.contentTop()));
		this.scroll = Mth.clamp(this.scroll, 0, max);
	}

	/** Runs {@code visitor} for each option row with its on-screen y. */
	private void forEachRow(RowVisitor visitor) {
		int y = this.contentTop() - (int) this.scroll;
		for (List<Opt> g : this.pages.get(this.page).groups()) {
			for (Opt opt : g) {
				visitor.visit(opt, y);
				y += ROW_H;
			}
			y += GROUP_GAP;
		}
	}

	@FunctionalInterface
	interface RowVisitor {
		void visit(Opt opt, int y);
	}

	private int sliderX() {
		return this.optionsX() + this.optionsW() - 96;
	}

	private record Btn(int x, int y, int w, Component label, boolean active) {
		boolean hit(double mx, double my) {
			return this.active && mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + 20;
		}
	}

	private List<Btn> footer() {
		int y = this.height - 26;
		int right = this.width - 10;
		boolean dirty = this.anyDirty();
		Btn done = new Btn(right - 70, y, 70, CommonComponents.GUI_DONE, true);
		Btn apply = new Btn(right - 146, y, 70, Component.translatable("basalt.button.apply"), dirty);
		Btn undo = new Btn(right - 222, y, 70, Component.translatable("basalt.button.undo"), dirty);
		return List.of(undo, apply, done);
	}

	// ---------------------------------------------------------------- self-test hooks

	public int pageCount() {
		return this.pages.size();
	}

	public int[] pageTabCenter(int i) {
		return new int[]{40, 42 + i * 20 + 10};
	}

	public int currentPage() {
		return this.page;
	}

	/** (option, row centre y) for the current page, at the current scroll. */
	public List<Object[]> rows() {
		List<Object[]> out = new ArrayList<>();
		this.forEachRow((opt, y) -> out.add(new Object[]{opt, y + ROW_H / 2}));
		return out;
	}

	public int rowX() {
		return this.optionsX() + 20;
	}

	public int sliderScreenX(double v) {
		return (int) Math.round(this.sliderX() + 2 + v * 86.0);
	}

	public int[] footerCenter(int i) {
		Btn b = this.footer().get(i);
		return new int[]{b.x() + b.w() / 2, b.y() + 10};
	}

	public boolean anyChanges() {
		return this.anyDirty();
	}

	public void scrollTo(double y) {
		this.scroll = y;
		this.clampScroll();
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int realMouseX, int realMouseY, float a) {
		final int mouseX = this.fakeMouse != null ? this.fakeMouse[0] : realMouseX;
		final int mouseY = this.fakeMouse != null ? this.fakeMouse[1] : realMouseY;
		this.clampScroll();

		// sidebar
		g.fill(0, 0, SIDEBAR_W, this.height, 0xC0000000);
		g.text(this.font, Component.literal("Basalt").withStyle(ChatFormatting.BOLD), 10, 10, ACCENT);
		g.text(this.font, Component.translatable("basalt.settings.subtitle"), 10, 22, 0xFF909090);
		for (int i = 0; i < this.pages.size(); i++) {
			int y = 42 + i * 20;
			boolean sel = i == this.page;
			boolean hov = mouseX < SIDEBAR_W && mouseY >= y && mouseY < y + 20;
			if (sel || hov) {
				g.fill(0, y, SIDEBAR_W, y + 20, sel ? 0x40FFFFFF : 0x20FFFFFF);
			}
			if (sel) {
				g.fill(0, y, 2, y + 20, ACCENT);
			}
			g.text(this.font, this.pages.get(i).title(), 12, y + 6, sel ? 0xFFFFFFFF : 0xFFBBBBBB);
		}

		// option rows
		int x0 = this.optionsX();
		int x1 = x0 + this.optionsW();
		this.hovered = null;
		g.enableScissor(x0, this.contentTop(), x1, this.contentBottom());
		this.forEachRow((opt, y) -> {
			if (y + ROW_H < this.contentTop() || y > this.contentBottom()) {
				return;
			}
			boolean hov = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + ROW_H
				&& mouseY >= this.contentTop() && mouseY < this.contentBottom();
			if (hov) {
				this.hovered = opt;
			}
			g.fill(x0, y, x1, y + ROW_H, hov ? 0xE0000000 : 0x90000000);
			Component name = opt.name();
			if (opt.dirty()) {
				name = name.copy().withStyle(ChatFormatting.ITALIC);
			}
			g.text(this.font, name, x0 + 6, y + 5, 0xFFFFFFFF);
			int valueColor = opt.dirty() ? 0xFFFFFF55 : 0xFFFFFFFF;
			if (opt.slider()) {
				int sx = this.sliderX();
				int sw = 90;
				int knob = sx + (int) Math.round(opt.sliderValue() * (sw - 4));
				g.fill(sx, y + 8, sx + sw, y + 10, 0xFF555555);
				g.fill(sx, y + 8, knob, y + 10, ACCENT);
				g.fill(knob, y + 4, knob + 4, y + 14, hov ? 0xFFFFFFFF : 0xFFCCCCCC);
				Component v = opt.valueText();
				g.text(this.font, v, sx - 6 - this.font.width(v), y + 5, valueColor);
			} else {
				Component v = opt.valueText();
				g.text(this.font, v, x1 - 6 - this.font.width(v), y + 5, valueColor);
			}
		});
		g.disableScissor();

		// scrollbar
		int total = this.contentHeight();
		int view = this.contentBottom() - this.contentTop();
		if (total > view) {
			int barH = Math.max(16, view * view / total);
			int barY = this.contentTop() + (int) (this.scroll * (view - barH) / (total - view));
			g.fill(x1 + 2, this.contentTop(), x1 + 5, this.contentBottom(), 0x60000000);
			g.fill(x1 + 2, barY, x1 + 5, barY + barH, 0xFFAAAAAA);
		}

		// description panel: to the right when there is room, otherwise a popup under the row
		Opt info = this.dragging != null ? this.dragging : this.hovered;
		if (info != null) {
			int px = x1 + 10;
			int pw = this.width - px - 10;
			boolean side = pw >= 110;
			if (!side) {
				px = x0 + 30;
				pw = Math.min(220, this.optionsW() - 30);
			}
			List<FormattedCharSequence> lines = new ArrayList<>();
			lines.addAll(this.font.split(info.name().copy().withStyle(ChatFormatting.BOLD), pw - 12));
			Component desc = info.description();
			if (desc != null) {
				lines.add(FormattedCharSequence.EMPTY);
				lines.addAll(this.font.split(desc, pw - 12));
			}
			if (info.needsRestart()) {
				lines.add(FormattedCharSequence.EMPTY);
				lines.addAll(this.font.split(Component.translatable("basalt.settings.restart.note").withStyle(ChatFormatting.YELLOW), pw - 12));
			}
			int ph = lines.size() * 10 + 12;
			int py = this.contentTop();
			if (!side) {
				int[] rowY = {mouseY};
				this.forEachRow((opt, y) -> {
					if (opt == info) {
						rowY[0] = y;
					}
				});
				py = rowY[0] + ROW_H + 2;
				if (py + ph > this.height - 4) {
					py = rowY[0] - ph - 2;
				}
			}
			if (!side) {
				g.nextStratum(); // popup must cover the row text underneath
			}
			g.fill(px, py, px + pw, py + ph, side ? 0xE0000000 : 0xFF141414);
			g.fill(px, py, px + 1, py + ph, ACCENT);
			for (int i = 0; i < lines.size(); i++) {
				g.text(this.font, lines.get(i), px + 7, py + 6 + i * 10, 0xFFE0E0E0);
			}
		}

		// footer
		if (this.restartPending()) {
			g.text(this.font, Component.translatable("basalt.settings.restart.note").withStyle(ChatFormatting.YELLOW), x0, this.height - 20, 0xFFFFFF55);
		}
		for (Btn b : this.footer()) {
			boolean hov = b.hit(mouseX, mouseY);
			g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 20, !b.active() ? 0x60000000 : hov ? 0xE0303030 : 0xC0000000);
			if (hov) {
				g.fill(b.x(), b.y() + 19, b.x() + b.w(), b.y() + 20, ACCENT);
			}
			g.centeredText(this.font, b.label(), b.x() + b.w() / 2, b.y() + 6, b.active() ? 0xFFFFFFFF : 0xFF707070);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// Sodium-style: the world stays visible behind translucent panels (no blur)
		if (this.minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, a);
		} else {
			g.fill(0, 0, this.width, this.height, 0x30000000);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (mx < SIDEBAR_W) {
			int i = (int) Math.floor((my - 42) / 20);
			if (my >= 42 && i >= 0 && i < this.pages.size()) {
				this.page = i;
				this.scroll = 0;
				this.playClick();
				return true;
			}
			return false;
		}
		List<Btn> f = this.footer();
		if (f.get(0).hit(mx, my)) {
			this.allOptions().forEach(Opt::undo);
			this.playClick();
			return true;
		}
		if (f.get(1).hit(mx, my)) {
			this.applyAll();
			this.playClick();
			return true;
		}
		if (f.get(2).hit(mx, my)) {
			this.playClick();
			this.onClose();
			return true;
		}
		Opt opt = this.optionAt(mx, my);
		if (opt != null) {
			if (opt.slider()) {
				this.dragging = opt;
				this.dragTo(opt, mx);
			} else {
				opt.cycle(button == 1 || this.minecraft.hasShiftDown());
			}
			this.playClick();
			return true;
		}
		return false;
	}

	private @Nullable Opt optionAt(double mx, double my) {
		int x0 = this.optionsX();
		if (mx < x0 || mx >= x0 + this.optionsW() || my < this.contentTop() || my >= this.contentBottom()) {
			return null;
		}
		Opt[] hit = new Opt[1];
		this.forEachRow((opt, y) -> {
			if (my >= y && my < y + ROW_H) {
				hit[0] = opt;
			}
		});
		return hit[0];
	}

	private void dragTo(Opt opt, double mx) {
		opt.setSlider((mx - this.sliderX() - 2) / 86.0);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging != null) {
			this.dragTo(this.dragging, event.x());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.dragging = null;
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll -= scrollY * ROW_H;
		this.clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return super.keyPressed(event);
	}

	private void playClick() {
		this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	@Override
	public void onClose() {
		this.applyAll();
		if (this.restartPending()) {
			this.minecraft.gui.setScreen(new AlertScreen(
				() -> this.minecraft.gui.setScreen(this.parent),
				Component.translatable("basalt.settings.restart.title").withStyle(ChatFormatting.YELLOW),
				Component.translatable("basalt.settings.restart.body")
			));
		} else {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
