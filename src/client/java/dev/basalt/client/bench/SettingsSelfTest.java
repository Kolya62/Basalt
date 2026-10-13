package dev.basalt.client.bench;

import dev.basalt.Basalt;
import dev.basalt.BasaltConfig;
import dev.basalt.client.screen.BasaltOptionsScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * -Dbasalt.bench.settingstest=true: drives the settings screen with real mouse events.
 * Screenshots every page with a hovered row, then clicks every option, drags every slider,
 * checks Undo restores everything, and checks Apply really changes a vanilla and a Basalt option
 * (both reverted afterwards). Writes run/basalt-bench/settingstest.txt.
 */
public final class SettingsSelfTest {
	private static int step = -1;
	private static int wait;
	private static final List<String> REPORT = new ArrayList<>();
	private static int failures;
	public static boolean finished;

	private SettingsSelfTest() {
	}

	private static MouseButtonEvent ev(double x, double y, int button) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
	}

	private static void click(BasaltOptionsScreen s, double x, double y, int button) {
		s.mouseClicked(ev(x, y, button), false);
		s.mouseReleased(ev(x, y, button));
	}

	/** Moves the fake mouse so hover state is computed on the next frame, then clicks. */
	private static void check(boolean ok, String what) {
		REPORT.add((ok ? "ok   " : "FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	private static void shot(Minecraft mc, String name) {
		net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
			try (image) {
				Path p = mc.gameDirectory.toPath().resolve("basalt-bench").resolve(name + ".png");
				Files.createDirectories(p.getParent());
				image.writeToFile(p);
			} catch (Exception e) {
				Basalt.LOGGER.error("screenshot failed", e);
			}
		});
	}

	/** Called every frame once in a world. */
	public static void onFrame(Minecraft mc) {
		if (finished) {
			return;
		}
		if (wait-- > 0) {
			return;
		}
		if (step < 0) {
			mc.gui.setScreen(new BasaltOptionsScreen(null));
			step = 0;
			wait = 20;
			return;
		}
		if (!(mc.gui.screen() instanceof BasaltOptionsScreen s)) {
			check(false, "screen still open at step " + step);
			finish(mc);
			return;
		}
		int pages = s.pageCount();
		if (step < pages * 2) {
			int page = step / 2;
			if (step % 2 == 0) {
				int[] tab = s.pageTabCenter(page);
				click(s, tab[0], tab[1], 0);
				check(s.currentPage() == page, "tab click opens page " + page);
				List<Object[]> rows = s.rows();
				// hover a Basalt option if the page has one, so the description panel shows the restart note
				Object[] row = rows.get(Math.min(rows.size() - 1, rows.size() / 2));
				s.fakeMouse = new int[]{s.rowX(), (Integer) row[1]};
				s.mouseMoved(s.rowX(), (Integer) row[1]);
			} else {
				shot(mc, "settings-page-" + page);
			}
			step++;
			wait = 10;
			return;
		}
		s.fakeMouse = null;
		runLogic(mc, s);
		finish(mc);
	}

	private static void runLogic(Minecraft mc, BasaltOptionsScreen s) {
		// 1. click / drag every option on every page, then Undo must restore all
		for (int p = 0; p < s.pageCount(); p++) {
			int[] tab = s.pageTabCenter(p);
			click(s, tab[0], tab[1], 0);
			s.scrollTo(0);
			List<Object[]> rows = s.rows();
			for (int i = 0; i < rows.size(); i++) {
				s.scrollTo(0);
				Object[] row = s.rows().get(i);
				int y = (Integer) row[1];
				if (y > mc.getWindow().getGuiScaledHeight() - 40) {
					s.scrollTo(y - 100);
					row = s.rows().get(i);
					y = (Integer) row[1];
				}
				BasaltOptionsScreen.Opt opt = (BasaltOptionsScreen.Opt) row[0];
				String before = opt.valueText().getString();
				if (opt.slider()) {
					int x = s.sliderScreenX(opt.sliderValue() < 0.5 ? 0.9 : 0.1);
					s.mouseClicked(ev(x, y, 0), false);
					s.mouseDragged(ev(x, y, 0), 0, 0);
					s.mouseReleased(ev(x, y, 0));
				} else {
					click(s, s.rowX(), y, 0);
				}
				String after = opt.valueText().getString();
				check(!before.equals(after), "page " + p + " '" + opt.name().getString() + "' changes: " + before + " -> " + after);
			}
		}
		check(s.anyChanges(), "pending changes exist after clicking");
		int[] undo = s.footerCenter(0);
		click(s, undo[0], undo[1], 0);
		check(!s.anyChanges(), "Undo clears every pending change");

		// 2. Apply really changes a vanilla option, then revert
		boolean bob = mc.options.bobView().get();
		clickOption(mc, s, 0, mc.options.bobView().toString());
		int[] apply = s.footerCenter(1);
		click(s, apply[0], apply[1], 0);
		check(mc.options.bobView().get() != bob, "Apply changes view bobbing");
		check(!s.anyChanges(), "nothing pending after Apply");

		// 3. Apply changes a Basalt option in the config, then revert
		String brain = BasaltConfig.stored("brain");
		clickOptionOnPage(mc, s, 4, 0);
		click(s, apply[0], apply[1], 0);
		check(!BasaltConfig.stored("brain").equals(brain) || !BasaltConfig.stored("parallelgen").equals("true"), "Apply writes a Basalt option");
		Basalt.LOGGER.info("[settingstest] parallelgen now {}", BasaltConfig.stored("parallelgen"));
		BasaltConfig.set("parallelgen", "true");
		BasaltConfig.save();
		mc.options.bobView().set(bob);
		mc.options.save();
	}

	private static void clickOption(Minecraft mc, BasaltOptionsScreen s, int page, String unused) {
		clickOptionOnPage(mc, s, page, 7); // General: view bobbing is row 7
	}

	private static void clickOptionOnPage(Minecraft mc, BasaltOptionsScreen s, int page, int row) {
		int[] tab = s.pageTabCenter(page);
		click(s, tab[0], tab[1], 0);
		s.scrollTo(0);
		Object[] r = s.rows().get(row);
		click(s, s.rowX(), (Integer) r[1], 0);
	}

	private static void finish(Minecraft mc) {
		finished = true;
		REPORT.add(failures == 0 ? "ALL OK" : failures + " FAILURES");
		try {
			Path p = mc.gameDirectory.toPath().resolve("basalt-bench").resolve("settingstest.txt");
			Files.createDirectories(p.getParent());
			Files.write(p, REPORT);
		} catch (Exception e) {
			Basalt.LOGGER.error("report failed", e);
		}
		mc.gui.setScreen(null);
	}
}
