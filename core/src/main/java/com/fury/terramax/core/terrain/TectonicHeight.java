package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;

/**
 * The surface as tectonics alone would leave it: crust base plus boundary relief.
 *
 * <p><b>This exists to break a cycle, and the cycle is the whole reason it is a
 * separate class rather than a method.</b> Regions are gated by climate, because
 * whether a landform survives as a mesa or is dissected into hills is decided by how
 * much rain falls on it. Climate is carried by wind and moisture, both of which read
 * terrain. If the terrain they read included regions, regions would depend on climate
 * and climate on regions, and neither could be computed first.
 *
 * <p>Splitting here resolves it, and resolves it in the physically correct place. The
 * causal order on a real planet is tectonics, then topography, then climate, then
 * erosion acting on that topography. Regions are an erosional outcome, so they belong
 * downstream of climate, and climate belongs downstream of the tectonic relief only.
 *
 * <p>It is also the right answer on the merits, independent of the cycle. A 45-block
 * rolling hill field must not deflect a continental airstream; a 1,400-block collision
 * range must. Wind was already tuned to ignore region relief, by taking its gradient
 * over 3,000 blocks so that anything varying over 2,300 largely cancels. This makes
 * that explicit in the types instead of leaving it to a constant that a later change
 * could quietly invalidate.
 *
 * <p>Pure function of position, as {@link HeightField} requires.
 */
public final class TectonicHeight implements HeightField {
	private final PlateMap plates;
	private final MountainRidge ridge;
	private final double blendWidthBlocks;

	public TectonicHeight(
			final long seed, final PlateMap plates, final TerrainSettings settings) {
		this.plates = plates;
		this.ridge = new MountainRidge(seed, settings, plates.settings().crustSpacingBlocks());
		this.blendWidthBlocks = settings.blendWidthBlocks(plates.settings().crustSpacingBlocks());
	}

	/**
	 * Everything one plate lookup yields, so callers need not repeat it.
	 *
	 * <p>The plate search is the expensive part of a column, and asking separately for
	 * the nearest boundary and for the relief ran it twice.
	 *
	 * @param plate       the nearest boundary and both sides of it
	 * @param base        crust base elevation, already blended toward the neighbour
	 * @param relief      mountains, arcs, trenches and rifts
	 * @param interiority 0 at a plate boundary, 1 once past the blend width
	 */
	public record Sample(PlateSample plate, double base, double relief, double interiority) {
		/** The tectonic surface here. */
		public double height() {
			return base + relief;
		}
	}

	public PlateMap plates() {
		return plates;
	}

	public double seaLevel() {
		return plates.settings().seaLevel();
	}

	public Sample sample(final double worldX, final double worldZ) {
		MountainRidge.Result ridged = ridge.evaluate(plates, worldX, worldZ);
		PlateSample plate = ridged.nearest();

		double interiority = smoothstep(plate.boundaryDistance() / blendWidthBlocks);

		return new Sample(plate, blendedBase(plate, interiority), ridged.relief(), interiority);
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		return sample(worldX, worldZ).height();
	}

	/**
	 * Plate base elevation, blended into the neighbour's near the boundary.
	 *
	 * <p>Without this the surface steps instantly from one plate's height to the
	 * next, producing a cliff along every boundary in the world. At the boundary
	 * itself both plates evaluate to the same midpoint, so the surface is continuous
	 * across it regardless of which side is queried.
	 */
	private static double blendedBase(final PlateSample sample, final double interiority) {
		double own = sample.crust().baseElevation();
		double neighbour = sample.neighbourCrust().baseElevation();
		double midpoint = (own + neighbour) * 0.5;

		return midpoint + (own - midpoint) * interiority;
	}

	static double smoothstep(final double x) {
		double t = Math.max(0.0, Math.min(1.0, x));

		return t * t * (3.0 - 2.0 * t);
	}
}
