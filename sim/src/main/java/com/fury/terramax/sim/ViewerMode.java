package com.fury.terramax.sim;

/**
 * What a click on the map does.
 *
 * <p>Modal rather than gestural. Cross sections were previously armed by
 * right-clicking twice, which nobody discovers without being told, and which fires by
 * accident whenever a pan happens not to move the mouse.
 */
public enum ViewerMode {
	/** Drag to pan. The default, and what the map does when no tool is chosen. */
	PAN("pan", "drag to pan, scroll to zoom"),

	/** Click two points to plot a profile between them. */
	SECTION("section", "click two points to plot a profile between them"),

	/** Click a point to report everything the generator knows about it. */
	PROBE("probe", "click a point to inspect it");

	private final String label;
	private final String hint;

	ViewerMode(final String label, final String hint) {
		this.label = label;
		this.hint = hint;
	}

	public String label() {
		return label;
	}

	/** Shown in the status bar, so the mode explains itself rather than needing docs. */
	public String hint() {
		return hint;
	}
}
