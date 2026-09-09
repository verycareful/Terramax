package com.fury.terramax.sim.probe;

/**
 * Somewhere worth looking, named by the probe that found it.
 *
 * <p><b>The reason the probe layer exists at all.</b> A census proves a landform is
 * present; only a render proves it is right; and the render needs somewhere to point.
 * Between those two facts sat an hour of this project's time, spent rendering a fixed
 * view at the origin that happened to contain none of the landform under test, and
 * then rendering the whole world at 820 blocks per pixel, where a 1,200-block feature
 * is one and a half pixels wide. Neither view showed anything, and neither said so.
 *
 * <p>A probe that knows where something is says so in this form. The command line
 * prints it; the viewer turns it into a button that moves the map there at a span
 * where the thing is actually visible. Carrying {@code spanBlocks} is what makes the
 * second half work: the probe knows the scale of what it found, and the viewer does
 * not have to guess.
 *
 * @param label      what was found here, in a few words
 * @param worldX     centre, in blocks
 * @param worldZ     centre, in blocks
 * @param spanBlocks width of a view that shows it properly
 */
public record Place(String label, double worldX, double worldZ, double spanBlocks) {
	public Place {
		if (spanBlocks <= 0.0) {
			throw new IllegalArgumentException("spanBlocks must be positive, got " + spanBlocks);
		}
	}

	@Override
	public String toString() {
		return String.format("%,.0f, %,.0f across %,.0f blocks   %s",
				worldX, worldZ, spanBlocks, label);
	}
}
