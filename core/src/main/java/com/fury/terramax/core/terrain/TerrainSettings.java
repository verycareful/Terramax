package com.fury.terramax.core.terrain;

/**
 * Tuning for terrain relief.
 *
 * <p>Widths are fractions of crust spacing so the whole system rescales together
 * when crust cell size changes. Heights are absolute blocks, because the
 * dimension's vertical range does not scale with anything horizontal.
 *
 * <p><b>{@code blendWidthFraction} and {@code rangeWidthFraction} are knowingly
 * stale.</b> They were tuned against 100,000-block plate spacing and are now
 * multiplied by 6,000-block crust spacing, so ranges come out roughly sixteen times
 * narrower than intended and mountains read as ridges rather than ranges. This is
 * left rather than guessed at, because the right values depend on the seven range
 * types and their zone sequences, which are a later slice. Expect the elevation
 * statistics to show very little terrain above y=1000 until then.
 *
 * @param blendWidthFraction       distance over which neighbouring crust bases blend, in crust spacings
 * @param rangeWidthFraction       half-width of a mountain range, in crust spacings
 * @param continentalCollisionRise peak rise where two continental plates collide, in blocks
 * @param subductionArcRise        peak rise on the overriding plate of a subduction zone
 * @param oceanicArcRise           peak rise where two oceanic plates converge
 * @param trenchDrop               depth of the trench on the subducting side
 * @param continentalRiftDrop      depth of a rift valley where continent pulls apart
 * @param oceanicRidgeRise         height of a mid-ocean ridge where ocean floor spreads
 * @param transformRelief          relief at transform margins, which build very little
 * @param reliefVariationFraction  how much relief varies along a range, as a fraction of its height
 * @param detailAmplitude          small-scale roughness applied everywhere above sea level
 * @param detailWavelength         wavelength of that roughness, in blocks
 * @param valleyDepth              maximum depth of erosive valley carving, in blocks
 * @param valleyWavelength         spacing of valleys, in blocks
 * @param regionReliefWavelengthFactor global multiplier on each region type's own
 *                                     declared wavelength, for tuning without
 *                                     editing the type table
 */
public record TerrainSettings(
		double blendWidthFraction,
		double rangeWidthFraction,
		double continentalCollisionRise,
		double subductionArcRise,
		double oceanicArcRise,
		double trenchDrop,
		double continentalRiftDrop,
		double oceanicRidgeRise,
		double transformRelief,
		double reliefVariationFraction,
		double detailAmplitude,
		double detailWavelength,
		double valleyDepth,
		double valleyWavelength,
		double regionReliefWavelengthFactor) {

	/**
	 * Defaults sized for the y=-256 to 1792 dimension.
	 *
	 * <p>The first cross-section showed terrain using only the bottom 15% of that
	 * range, with land 0 to 112 blocks above sea level. These numbers are chosen to
	 * fill it: a continental collision rising 1,400 blocks above a base near y=112
	 * reaches roughly y=1500, which uses the height the dimension is paying for.
	 *
	 * <p>Range half-width is 0.10 of plate spacing, so 10,000 blocks at the default.
	 * That is proportionate: the Himalayas are around 250km across against plates
	 * some thousands of km wide.
	 *
	 * <p><b>Trenches are shallow, and not by choice.</b> With sea level at y=0 and
	 * the dimension floor at y=-256 there are 256 blocks of depth available,
	 * against 1,792 blocks of sky. Earth is roughly symmetric: Everest at 8.8km
	 * against the Mariana Trench at -11km. An earthlike trench here would punch
	 * through the bottom of the world. The oceanic base already sits as low as -160,
	 * so -80 is close to all the room left. Deepening trenches means raising sea
	 * level, which spends buildable height to buy ocean depth.
	 */
	public static TerrainSettings defaults() {
		return new TerrainSettings(
				0.30,
				0.10,
				1400.0,
				900.0,
				420.0,
				-80.0,
				-180.0,
				260.0,
				60.0,
				0.45,
				28.0,
				900.0,
				90.0,
				7000.0,
				1.0);
	}

	public double blendWidthBlocks(final double crustSpacing) {
		return crustSpacing * blendWidthFraction;
	}

	public double rangeWidthBlocks(final double crustSpacing) {
		return crustSpacing * rangeWidthFraction;
	}
}
