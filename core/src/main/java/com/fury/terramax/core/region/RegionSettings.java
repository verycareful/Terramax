package com.fury.terramax.core.region;

/**
 * Tuning for the region lattice.
 *
 * @param spacingBlocks        mean distance between region centres
 * @param jitter               region centre offset as a fraction of spacing, in [0, 0.5]
 * @param warpStrengthBlocks   coordinate displacement, which stops regions being convex
 * @param warpWavelengthFactor displacement field wavelength, in region spacings
 * @param warpOctaves          detail in the displacement field
 * @param blendFraction        region blend width as a fraction of spacing
 * @param provinceWavelengthBlocks scale over which regions of a type share a level
 * @param provinceWeight       share of a region's height taken from the province
 *                             field rather than from its own roll, in [0, 1]
 */
public record RegionSettings(
		double spacingBlocks,
		double jitter,
		double warpStrengthBlocks,
		double warpWavelengthFactor,
		int warpOctaves,
		double blendFraction,
		double provinceWavelengthBlocks,
		double provinceWeight) {

	public double warpWavelengthBlocks() {
		return spacingBlocks * warpWavelengthFactor;
	}

	public double blendWidthBlocks() {
		return spacingBlocks * blendFraction;
	}

	/**
	 * Defaults giving roughly six or seven regions per 6,000-block crust cell.
	 *
	 * <p>{@code spacingBlocks} 2,300 comes from that density: a 6,000-block cell
	 * covers 36 million blocks, and six or seven regions in it makes each about
	 * 2,300 across. At this world's horizontal compression that is roughly 115 km on
	 * the ground, bigger than the Cotswolds and about the size of the Ozarks, and
	 * about four minutes to cross on foot. Comfortably larger than a vanilla biome,
	 * which is the point.
	 *
	 * <p><b>The lattice is global, not nested inside crust cells.</b> Giving each
	 * crust cell its own six or seven regions would print a 6,000-block grid of
	 * seams across the entire world, and because that alignment is systematic rather
	 * than random, no amount of jitter or warping hides it. A global lattice at the
	 * same density gives the same result with nothing lining up. Crust cells still
	 * supply context: an oceanic cell cannot produce rolling hills.
	 *
	 * <p>{@code blendFraction} 0.22 leaves roughly half of each region at its own
	 * full height with the rest transitioning. Larger and regions lose their
	 * identity; smaller and the edges read as steps.
	 *
	 * <p>{@code provinceWavelengthBlocks} 25,000 is about ten regions across, so a
	 * province holds a coherent group rather than a single region or a continent. At
	 * 0.75 the province decides most of a region's height and its own roll decides the
	 * rest, which keeps a plateau province recognisably one surface without flattening
	 * it into a single table.
	 */
	public static RegionSettings defaults() {
		return new RegionSettings(2_300.0, 0.4, 1_600.0, 2.0, 3, 0.22, 25_000.0, 0.75);
	}
}
