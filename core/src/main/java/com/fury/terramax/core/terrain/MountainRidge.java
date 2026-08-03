package com.fury.terramax.core.terrain;

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
	/** Separates relief variation from every other noise field. */
	private static final long SALT_RELIEF = 0x9E3779B185EBCA87L;

	/** Octaves in the along-range variation. Few, because it should undulate, not fizz. */
	private static final int RELIEF_OCTAVES = 3;

	/** Along-range variation wavelength, in plate spacings. */
	private static final double RELIEF_WAVELENGTH_FACTOR = 0.55;

	private final TerrainSettings settings;
	private final double rangeWidthBlocks;
	private final FractalNoise2D reliefVariation;

	public MountainRidge(final long seed, final TerrainSettings settings, final double plateSpacing) {
		this.settings = settings;
		this.rangeWidthBlocks = settings.rangeWidthBlocks(plateSpacing);
		this.reliefVariation = FractalNoise2D.standard(
				seed ^ SALT_RELIEF, RELIEF_OCTAVES, plateSpacing * RELIEF_WAVELENGTH_FACTOR);
	}

	/** Height offset in blocks at the given position. */
	public double offsetAt(final PlateSample sample, final double worldX, final double worldZ) {
		double falloff = falloff(sample.boundaryDistance());

		if (falloff <= 0.0) {
			return 0.0;
		}

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
		double variation = 1.0 + reliefVariation.sample(worldX, worldZ) * settings.reliefVariationFraction();

		return peak * falloff * motion * Math.max(0.0, variation);
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
