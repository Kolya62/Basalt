package dev.basalt.client.textcache;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

/**
 * World text (signs, name tags) is laid out into glyphs every frame although the text object is
 * the same: SignText keeps its FormattedCharSequence until the sign is edited. This keeps the laid
 * out glyph list per text object and layout parameters (render thread only). Obfuscated text is
 * never cached (its glyphs change every frame); a font reload clears everything.
 */
public final class TextCache {
	private static final Map<FormattedCharSequence, Entry> CACHE = new IdentityHashMap<>();
	private static int frame;

	private record Entry(Font font, float x, float y, int color, boolean shadow, boolean includeEmpty, int background, Font.PreparedText text, int[] lastUse) {
		boolean matches(Font f, float x, float y, int c, boolean s, boolean e, int b) {
			return this.font == f && Float.compare(this.x, x) == 0 && Float.compare(this.y, y) == 0 && this.color == c
				&& this.shadow == s && this.includeEmpty == e && this.background == b;
		}
	}

	private TextCache() {
	}

	public static Font.PreparedText get(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int background,
		Supplier<Font.PreparedText> build) {
		Entry e = CACHE.get(text);
		if (e != null && e.matches(font, x, y, color, shadow, includeEmpty, background)) {
			e.lastUse()[0] = frame;
			return e.text();
		}
		Font.PreparedText built = build.get();
		boolean[] obfuscated = {false};
		text.accept((pos, style, cp) -> {
			if (style.isObfuscated()) {
				obfuscated[0] = true;
				return false;
			}
			return true;
		});
		if (!obfuscated[0]) {
			CACHE.put(text, new Entry(font, x, y, color, shadow, includeEmpty, background, built, new int[]{frame}));
		}
		return built;
	}

	public static void endFrame() {
		if (++frame % 120 == 0) {
			for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext(); ) {
				if (frame - it.next().lastUse()[0] > 120) {
					it.remove();
				}
			}
		}
	}

	public static void clear() {
		CACHE.clear();
	}
}
