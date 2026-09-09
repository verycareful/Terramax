package com.fury.terramax.sim;

/**
 * The dimension's vertical extent, and sea level within it.
 *
 * <p>These lived on {@code MapPanel}, which made every consumer of them depend on a
 * Swing component. A measurement that runs headless has no business importing a
 * panel, and once probes moved out of the batch renderer that stopped being a
 * stylistic complaint and became a compile error waiting to happen.
 *
 * <p>They are not settings. Nothing tunes them, they match the dimension the mod
 * declares, and a render, a cross section and a probe all have to agree on them or
 * they are describing different worlds.
 */
public final class WorldBounds {
	/** Floor of the dimension. */
	public static final int MIN_Y = -256;

	/** Ceiling of the dimension. */
	public static final int MAX_Y = 1792;

	/** Sea level, which is also the continental crust base. */
	public static final int SEA_LEVEL = 0;

	private WorldBounds() {
	}

	/** Total buildable height, floor to ceiling. */
	public static int height() {
		return MAX_Y - MIN_Y;
	}
}
