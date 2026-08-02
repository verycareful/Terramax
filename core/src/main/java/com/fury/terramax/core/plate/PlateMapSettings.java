package com.fury.terramax.core.plate;

/**
 * Tuning for the plate system. Every number the plate model depends on lives
 * here, so the simulator and the mod cannot drift apart by disagreeing on a
 * constant.
 *
 * @param spacingBlocks        mean distance between plate centres
 * @param jitter               plate centre offset as a fraction of spacing, in [0, 0.5]
 * @param continentalFraction  proportion of plates carrying continent, in [0, 1]
 * @param seaLevel             world Y of sea level
 * @param continentalBase      mean world Y of continental plate interiors
 * @param oceanicBase          mean world Y of ocean floor
 * @param baseVariation        per-plate elevation spread around its type's base, in blocks
 * @param transformDominance   how far shear must exceed convergence before a boundary
 *                             counts as transform rather than convergent or divergent
 */
public record PlateMapSettings(
		double spacingBlocks,
		double jitter,
		double continentalFraction,
		int seaLevel,
		int continentalBase,
		int oceanicBase,
		int baseVariation,
		double transformDominance) {

	public PlateMapSettings {
		if (continentalFraction < 0.0 || continentalFraction > 1.0) {
			throw new IllegalArgumentException(
					"continentalFraction must be in [0, 1], got " + continentalFraction);
		}

		if (transformDominance < 1.0) {
			throw new IllegalArgumentException(
					"transformDominance must be at least 1.0, got " + transformDominance
							+ ". Below 1.0 transform boundaries outnumber every other kind.");
		}
	}

	/**
	 * Defaults for the design's 100,000-block plates in a -256 to 1792 world.
	 *
	 * <p>{@code continentalFraction} is 0.62, well above Earth's roughly 0.4, and
	 * deliberately so. At this spacing a single oceanic plate is 100,000 blocks of
	 * open water, which is around 55 minutes of elytra flight. Earthlike ocean
	 * proportions would make the world hostile to cross rather than realistic.
	 *
	 * <p>{@code transformDominance} is 3.0. The share of transform boundaries is
	 * {@code 1 - (2/pi) * atan(k)}, so k=1 gives 50%, k=3 gives about 20%, and k=4
	 * about 16%. Earth sits near 15% by boundary length. 3.0 lands close to that
	 * while leaving convergent and divergent margins, the ones that actually build
	 * relief, as the clear majority.
	 */
	public static PlateMapSettings defaults() {
		return new PlateMapSettings(
				100_000.0,
				0.35,
				0.62,
				64,
				112,
				-96,
				64,
				3.0);
	}

	public PlateMapSettings withSpacing(final double newSpacingBlocks) {
		return new PlateMapSettings(
				newSpacingBlocks, jitter, continentalFraction,
				seaLevel, continentalBase, oceanicBase, baseVariation, transformDominance);
	}

	public PlateMapSettings withContinentalFraction(final double newContinentalFraction) {
		return new PlateMapSettings(
				spacingBlocks, jitter, newContinentalFraction,
				seaLevel, continentalBase, oceanicBase, baseVariation, transformDominance);
	}

	public PlateMapSettings withTransformDominance(final double newTransformDominance) {
		return new PlateMapSettings(
				spacingBlocks, jitter, continentalFraction,
				seaLevel, continentalBase, oceanicBase, baseVariation, newTransformDominance);
	}
}
