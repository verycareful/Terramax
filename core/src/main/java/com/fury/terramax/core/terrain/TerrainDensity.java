package com.fury.terramax.core.terrain;

/**
 * Converts surface elevation into the 3D density field a chunk generator carves.
 *
 * <p>Positive density is solid, negative is air, and the transition is smoothed
 * over a few blocks so the generator has a gradient to work with rather than a
 * hard step.
 *
 * <p><b>Why there is no binary search.</b> The design document specified a
 * heightmap-first compositor that finds the surface by sampling density at coarse
 * vertical intervals and refining. That is the right approach when density is
 * genuinely three-dimensional, because the surface is then implicit and can only
 * be found by looking. Terramax's terrain is a {@link HeightField}: the surface is
 * computed directly, so searching for it would be looking up an answer already in
 * hand. If overhangs or floating terrain are added later, density stops being a
 * function of {@code (x, z)} alone and the search comes back.
 *
 * <p>What does survive is the column-first shape. Evaluating {@link HeightField}
 * costs plate lookups, domain warping and several noise fields; doing that once
 * per column instead of once per block is the difference between a playable
 * generator and an unplayable one. Callers should take a {@link Column} and reuse
 * it down the whole vertical extent.
 */
public final class TerrainDensity {
	private final HeightField surface;
	private final double transitionBlocks;

	/**
	 * @param surface          elevation to carve to
	 * @param transitionBlocks vertical distance over which density goes from fully
	 *                         solid to fully air; larger values give softer surfaces
	 */
	public TerrainDensity(final HeightField surface, final double transitionBlocks) {
		if (transitionBlocks <= 0.0) {
			throw new IllegalArgumentException(
					"transitionBlocks must be positive, got " + transitionBlocks);
		}

		this.surface = surface;
		this.transitionBlocks = transitionBlocks;
	}

	/**
	 * Resolves one column's surface, once.
	 *
	 * <p>The returned value is cheap to query at any height, so a chunk generator
	 * should hold one per {@code (x, z)} and walk it vertically.
	 */
	public Column column(final double worldX, final double worldZ) {
		return new Column(surface.heightAt(worldX, worldZ), transitionBlocks);
	}

	/**
	 * Convenience for one-off queries. Recomputes the surface every call, so it is
	 * the wrong thing to use inside a vertical loop.
	 */
	public double densityAt(final double worldX, final double y, final double worldZ) {
		return column(worldX, worldZ).densityAt(y);
	}

	/**
	 * One column's surface height, and the density profile above and below it.
	 *
	 * @param surfaceY         world Y of the surface in this column
	 * @param transitionBlocks vertical softening distance
	 */
	public record Column(double surfaceY, double transitionBlocks) {
		/** Density at a height. Positive is solid, negative is air, clamped to [-1, 1]. */
		public double densityAt(final double y) {
			double signed = (surfaceY - y) / transitionBlocks;

			return Math.max(-1.0, Math.min(1.0, signed));
		}

		/** True if this height is inside terrain. */
		public boolean isSolid(final double y) {
			return y <= surfaceY;
		}
	}
}
