package dev.basalt.client.compactvertex;

/**
 * Quads of one section layer, regrouped by facing: groups 0..5 = DOWN, UP, NORTH, SOUTH, WEST, EAST
 * (axis-aligned planar quads), 6 = everything else. Groups are stored consecutively in that order.
 * plane[g * 2] / plane[g * 2 + 1] = min / max plane coordinate (section-relative) of group g.
 */
public record FacingInfo(int[] quadCounts, float[] plane) {
	public int startQuad(int group) {
		int s = 0;
		for (int g = 0; g < group; g++) {
			s += this.quadCounts[g];
		}
		return s;
	}

	/** True if every face in group g is back-facing for a camera at (cx, cy, cz) relative to the section origin. */
	public boolean hidden(int group, double cx, double cy, double cz) {
		float min = this.plane[group * 2], max = this.plane[group * 2 + 1];
		return switch (group) {
			case 0 -> cy > max; // DOWN faces are seen from below their plane
			case 1 -> cy < min; // UP faces from above
			case 2 -> cz > max; // NORTH (-Z)
			case 3 -> cz < min; // SOUTH (+Z)
			case 4 -> cx > max; // WEST (-X)
			case 5 -> cx < min; // EAST (+X)
			default -> false;
		};
	}
}
