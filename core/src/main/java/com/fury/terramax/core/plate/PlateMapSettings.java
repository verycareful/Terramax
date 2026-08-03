package com.fury.terramax.core.plate;

/**
 * Tuning for the plate system. Every number the plate model depends on lives
 * here, so the simulator and the mod cannot drift apart by disagreeing on a
 * constant.
 *
 * <p>Lengths that should scale with crust cell size are expressed as multiples of
 * {@code crustSpacingBlocks} rather than as absolute distances. Changing crust
 * spacing then rescales the whole system coherently instead of leaving warp and
 * continent sizes stranded at their old values.
 *
 * @param crustSpacingBlocks        mean distance between crust cell centres
 * @param jitter                    crust cell offset as a fraction of spacing, in [0, 0.5]
 * @param continentalFraction       proportion of crust carrying continent, in [0, 1]
 * @param seaLevel                  world Y of sea level
 * @param continentalBase           mean world Y of continental crust interiors
 * @param oceanicBase               mean world Y of ocean floor
 * @param baseVariation             per-cell elevation spread around its type's base, in blocks
 * @param transformDominance        how far shear must exceed convergence for a transform boundary
 * @param warp                      coordinate displacement, which breaks up the Voronoi geometry
 * @param continentWavelengthFactor wavelength of the land/ocean field, in crust spacings
 */
public record PlateMapSettings(
		double crustSpacingBlocks,
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
	 * Domain warp tuning, in units that scale with crust spacing.
	 *
	 * @param strengthFraction displacement as a fraction of crust spacing
	 * @param wavelengthFactor displacement field wavelength, in crust spacings
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
		return crustSpacingBlocks * warp.strengthFraction();
	}

	public double warpWavelengthBlocks() {
		return crustSpacingBlocks * warp.wavelengthFactor();
	}

	public double continentWavelengthBlocks() {
		return crustSpacingBlocks * continentWavelengthFactor;
	}

	/**
	 * Defaults for a two-lattice world with sea level at 0.
	 *
	 * <p><b>Sea level is 0, not 64.</b> The 64 was inherited from vanilla rather
	 * than chosen. Oceans are not worth vertical budget here, so the whole 1,792
	 * blocks above zero go to land, and as a bonus y now reads directly as altitude
	 * above sea level, which makes every number in the codebase self-explanatory.
	 *
	 * <p>{@code crustSpacingBlocks} is 6,000. This is the granularity of plate
	 * outlines and of crust type, not the size of a plate: plates are groups of
	 * these cells. Six thousand means even a small plate is several cells across
	 * and therefore has a ragged outline rather than being one square.
	 *
	 * <p>{@code continentWavelengthFactor} is now expressed in crust spacings and
	 * has to grow accordingly. At 40.0 the land/ocean field correlates over roughly
	 * 240,000 blocks, which is the continent-sized scale the old value gave when
	 * spacing was 100,000.
	 */
	public static PlateMapSettings defaults() {
		return new PlateMapSettings(
				6_000.0,
				0.35,
				0.62,
				0,
				112,
				-96,
				64,
				3.0,
				new Warp(2.5, 1.6, 3),
				40.0);
	}
}
