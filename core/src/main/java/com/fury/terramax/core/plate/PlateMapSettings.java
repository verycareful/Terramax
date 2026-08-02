package com.fury.terramax.core.plate;

/**
 * Tuning for the plate system. Every number the plate model depends on lives
 * here, so the simulator and the mod cannot drift apart by disagreeing on a
 * constant.
 *
 * <p>Lengths that should scale with plate size are expressed as multiples of
 * {@code spacingBlocks} rather than as absolute distances. Changing plate spacing
 * then rescales the whole system coherently instead of leaving warp and continent
 * sizes stranded at their old values.
 *
 * @param spacingBlocks             mean distance between plate centres
 * @param jitter                    plate centre offset as a fraction of spacing, in [0, 0.5]
 * @param continentalFraction       proportion of plates carrying continent, in [0, 1]
 * @param seaLevel                  world Y of sea level
 * @param continentalBase           mean world Y of continental plate interiors
 * @param oceanicBase               mean world Y of ocean floor
 * @param baseVariation             per-plate elevation spread around its type's base, in blocks
 * @param transformDominance        how far shear must exceed convergence for a transform boundary
 * @param warp                      coordinate displacement, which breaks up the Voronoi geometry
 * @param continentWavelengthFactor wavelength of the land/ocean field, in plate spacings
 */
public record PlateMapSettings(
		double spacingBlocks,
		double jitter,
		double continentalFraction,
		int seaLevel,
		int continentalBase,
		int oceanicBase,
		int baseVariation,
		double transformDominance,
		Warp warp,
		double continentWavelengthFactor) {

	/**
	 * Domain warp tuning, in units that scale with plate spacing.
	 *
	 * @param strengthFraction displacement as a fraction of plate spacing
	 * @param wavelengthFactor displacement field wavelength, in plate spacings
	 * @param octaves          detail in the displacement field
	 */
	public record Warp(double strengthFraction, double wavelengthFactor, int octaves) {
		public Warp {
			if (strengthFraction < 0.0) {
				throw new IllegalArgumentException(
						"strengthFraction must not be negative, got " + strengthFraction);
			}

			if (octaves < 1) {
				throw new IllegalArgumentException("octaves must be at least 1, got " + octaves);
			}
		}
	}

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

	public double warpStrengthBlocks() {
		return spacingBlocks * warp.strengthFraction();
	}

	public double warpWavelengthBlocks() {
		return spacingBlocks * warp.wavelengthFactor();
	}

	public double continentWavelengthBlocks() {
		return spacingBlocks * continentWavelengthFactor;
	}

	/**
	 * Defaults for the design's 100,000-block plates in a -256 to 1792 world.
	 *
	 * <p>{@code continentalFraction} is 0.62, well above Earth's roughly 0.4, and
	 * deliberately so. At this spacing a single oceanic plate is 100,000 blocks of
	 * open water, around 55 minutes of elytra flight. Earthlike ocean proportions
	 * would make the world hostile to cross rather than realistic.
	 *
	 * <p>{@code transformDominance} is 3.0. The transform share is
	 * {@code 1 - (2/pi) * atan(k)}: k=1 gives 50%, k=3 about 20%, k=4 about 16%.
	 * Earth sits near 15% by boundary length.
	 *
	 * <p>Warp strength is 0.22 of spacing, so 22,000 blocks at the default. Large
	 * enough to make boundaries genuinely sinuous, small enough that a plate is
	 * still recognisably one region rather than smeared into its neighbours.
	 *
	 * <p>{@code continentWavelengthFactor} is 4.0, so land and ocean correlate over
	 * roughly four plates. That produces continents of several plates rather than
	 * the salt-and-pepper mixing an independent per-plate coin flip gives.
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
				3.0,
				new Warp(0.22, 1.6, 3),
				4.0);
	}
}
