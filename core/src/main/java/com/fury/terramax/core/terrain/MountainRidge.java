package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.util.FractalNoise2D;

/**
 * Relief created at plate boundaries.
 *
 * <p>Returns a height offset in blocks, added to the smoothed plate base. The sign
 * matters: convergent margins push terrain up, divergent margins pull it down on
 * land and up on the ocean floor, and trenches go sharply negative.
 *
 * <p>The two sides of a subduction zone are deliberately asymmetric. Where oceanic
 * crust dives beneath continental, the continental side gains a mountain arc and
 * the oceanic side gains a trench. Treating both sides identically, which a naive
 * distance-to-boundary function does, produces a symmetric ridge that looks
 * nothing like a real margin.
 */
public final class MountainRidge {
	/** Separates the two ridge fields from each other and from everything else. */
	private static final long SALT_RELIEF = 0x9E3779B185EBCA87L;
	private static final long SALT_GRAIN = 0x3C79AC492BA7B653L;

	/** Octaves in the along-range variation. Few, because it should undulate, not fizz. */
	private static final int RELIEF_OCTAVES = 3;

	/** Along-range variation wavelength, in crust spacings. */
	private static final double RELIEF_WAVELENGTH_FACTOR = 0.55;

	/**
	 * How sharply ridges are pinched.
	 *
	 * <p>{@code 1 - |noise|} already peaks along the zero crossings of the field,
	 * which is what makes a connected line of ridges rather than isolated bumps.
	 * Raising it above 1 narrows the crests and widens the valleys between them, which
	 * is the difference between rolling swells and mountains.
	 */
	private static final double GRAIN_SHARPNESS = 1.6;

	private final TerrainSettings settings;
	private final double rangeWidthBlocks;
	private final FractalNoise2D reliefVariation;
	private final FractalNoise2D grain;

	public MountainRidge(final long seed, final TerrainSettings settings, final double crustSpacing) {
		this.settings = settings;
		this.rangeWidthBlocks = settings.rangeWidthBlocks(crustSpacing);
		this.reliefVariation = FractalNoise2D.standard(
				seed ^ SALT_RELIEF, RELIEF_OCTAVES, crustSpacing * RELIEF_WAVELENGTH_FACTOR);

		// Unit wavelength: this field is fed coordinates already divided by the
		// grain's own across and along wavelengths, which is what makes the sampling
		// anisotropic. Building it at a single wavelength would force both axes to
		// share one and there would be no grain.
		this.grain = FractalNoise2D.standard(
				seed ^ SALT_GRAIN, settings.grain().octaves(), 1.0);
	}

	/**
	 * Total relief at a position, combined over every plate boundary within reach.
	 *
	 * <p>Combining rather than taking the nearest boundary alone is a correctness fix,
	 * not a refinement. Where the nearest differing plate changes, the bisector jumps
	 * and so does the distance, so a nearest-only range went from full height to
	 * nothing between adjacent columns: cross sections showed vertical walls a
	 * thousand blocks tall.
	 *
	 * <p><b>Averaged and then scaled by the strongest falloff</b>, rather than summed.
	 * A plain sum is continuous but unbounded: three collision ranges meeting at a
	 * triple junction add to 4,200 blocks and terrain reached y=5,358 against a
	 * ceiling of 1,792. This form keeps both properties that matter. It still fades to
	 * zero at the range edge, because the scaling falloff does. And two overlapping
	 * ranges average instead of stacking, so the result is bounded by the tallest
	 * single contribution.
	 *
	 * <p>Continuity is free either way: a boundary entering or leaving the set does so
	 * with a falloff of exactly zero, contributing nothing to numerator, denominator
	 * or maximum.
	 */
	public double totalOffsetAt(final PlateMap plates, final double worldX, final double worldZ) {
		// A three-element array because a lambda cannot close over mutable locals:
		// weighted relief, total weight, strongest weight.
		double[] acc = new double[3];

		plates.forEachBoundary(worldX, worldZ, rangeWidthBlocks, boundary -> {
			double falloff = falloff(boundary.boundaryDistance());

			if (falloff <= 0.0) {
				return;
			}

			acc[0] += falloff * reliefAt(boundary, worldX, worldZ);
			acc[1] += falloff;
			acc[2] = Math.max(acc[2], falloff);
		});

		if (acc[1] <= 0.0) {
			return 0.0;
		}

		return acc[0] / acc[1] * acc[2];
	}

	/**
	 * Relief this boundary would build at full strength, before any distance falloff.
	 *
	 * <p>Separated from the falloff so {@link #totalOffsetAt} can weight several
	 * boundaries against each other. Multiplying the falloff in here instead would
	 * make the average count near boundaries and far ones equally.
	 */
	private double reliefAt(final PlateSample sample, final double worldX, final double worldZ) {
		double peak = peakRelief(sample);

		if (peak == 0.0) {
			return 0.0;
		}

		// Relative motion scales relief: plates barely converging build barely
		// anything. Magnitude is bounded by construction, so this stays in [0, 1].
		double motion = Math.min(1.0, Math.abs(sample.convergence()) + sample.shear());

		// Vary height along the range so it is not a uniform wall. Without this a
		// mountain range is the same height for its entire length, which reads as
		// artificial more immediately than almost anything else.
		double variation = 1.0 + reliefVariation.sample(worldX, worldZ)
				* settings.reliefVariationFraction();

		return peak * motion * Math.max(0.0, variation) * grainFactor(sample);
	}

	/**
	 * Carves parallel ridges and valleys into the range envelope.
	 *
	 * <p>Sampled in the boundary's own frame with the across-axis compressed and the
	 * along-axis stretched, so every feature comes out {@code alongFactor} times
	 * longer than it is wide and aligned with the range. This is the entire mechanism:
	 * there is no ridge generator, only ordinary noise read through an anisotropic
	 * coordinate system.
	 *
	 * <p>Returns a multiplier in {@code [1 - depth, 1]}, so crests keep the full
	 * envelope height and valley floors drop to a fraction of it. Multiplicative
	 * rather than additive because a valley should cut proportionally: a 200-block
	 * range gets 200-block-scale valleys and a 1,400-block range gets deep ones,
	 * which is the erosion argument for scaling relief with local relief.
	 */
	private double grainFactor(final PlateSample sample) {
		var g = settings.grain();

		double ridged = 1.0 - Math.abs(grain.sample(
				sample.boundaryDistance() / g.acrossWavelength(),
				sample.alongBoundary() / g.alongWavelength()));

		double crest = Math.pow(Math.max(0.0, ridged), GRAIN_SHARPNESS);

		return 1.0 - g.depth() * (1.0 - crest);
	}

	/**
	 * Peak relief for this boundary, in blocks, before falloff and motion scaling.
	 *
	 * <p>Sign convention: positive rises, negative drops.
	 */
	private double peakRelief(final PlateSample sample) {
		return switch (sample.boundaryType()) {
			case CONVERGENT -> convergentRelief(sample);
			case DIVERGENT -> sample.crust().isContinental()
					? settings.continentalRiftDrop()
					: settings.oceanicRidgeRise();
			case TRANSFORM -> settings.transformRelief();

			// A seam inside a plate. Falloff already returns zero at its infinite
			// boundary distance, so this is belt and braces, but it keeps the switch
			// exhaustive and states the intent rather than relying on a distance.
			case NONE -> 0.0;
		};
	}

	private double convergentRelief(final PlateSample sample) {
		if (sample.isContinentalCollision()) {
			return settings.continentalCollisionRise();
		}

		if (sample.isSubducting()) {
			// Asymmetric: arc on the overriding continental side, trench on the
			// oceanic side that is being driven under.
			return sample.isOverridingPlate()
					? settings.subductionArcRise()
					: settings.trenchDrop();
		}

		// Oceanic against oceanic: island arcs, far smaller than a continental range.
		return settings.oceanicArcRise();
	}

	/**
	 * Smooth dome centred on the boundary, reaching zero at the range edge.
	 *
	 * <p>Uses the complement of smoothstep, whose derivative is zero at both ends.
	 * A linear falloff would leave a visible crease where the range meets the plain,
	 * and a sharp peak at the boundary would produce a razor-edged wall rather than
	 * a crest.
	 */
	private double falloff(final double boundaryDistance) {
		if (boundaryDistance >= rangeWidthBlocks) {
			return 0.0;
		}

		double t = boundaryDistance / rangeWidthBlocks;

		return 1.0 - (t * t * (3.0 - 2.0 * t));
	}
}
