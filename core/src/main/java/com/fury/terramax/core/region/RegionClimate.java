package com.fury.terramax.core.region;

import com.fury.terramax.core.climate.MoistureField;

/**
 * How dry it is where a region sits, which decides what kind of region it can be.
 *
 * <p><b>Climate does not choose landforms, it chooses which ones survive.</b> That
 * distinction is the whole design here. Plateaus exist in the Sahara, in Tibet and in
 * wet Brazil, so rainfall plainly does not decide whether ground is lifted and flat.
 * What rainfall decides is <em>dissection</em>. An escarpment standing in a desert
 * stays an escarpment for tens of millions of years; the same escarpment under a metre
 * of rain a year is cut into valleys and rounded into hills within a fraction of that.
 *
 * <p>So the gate is not "deserts get mesas". It is that mesas and inselbergs are what
 * is <em>left</em> where nothing erodes them, and rolling hills are what everything
 * becomes where things do. Monument Valley, Uluru and the Sahel tablelands are all arid
 * for the same reason, and it is not a coincidence anyone had to arrange.
 *
 * <p><b>This also fixes the patchwork, and that is not a side effect.</b> Region types
 * were rolled independently per cell, so a 900-block plateau could abut a 5-block
 * plain with nothing in between. Precipitation varies over tens of thousands of blocks
 * while regions are 2,300 apart, so neighbouring regions necessarily share a climate
 * and therefore share a bias. Coherence comes from the gate being a smooth field, not
 * from any smoothing applied afterwards.
 */
@FunctionalInterface
public interface RegionClimate {
	/**
	 * Dryness at a position: 0 where erosion is fastest, 1 where nothing wears down.
	 *
	 * <p>Sampled at a region's own centre rather than at the query point, so every
	 * part of one region agrees about what kind of region it is.
	 */
	double aridityAt(double worldX, double worldZ);

	/**
	 * Everywhere equally middling, for callers with no climate model yet.
	 *
	 * <p>Reproduces the ungated behaviour: every type keeps the mean of its two
	 * weights, so nothing is forbidden and nothing is favoured.
	 */
	RegionClimate NEUTRAL = (worldX, worldZ) -> 0.5;

	/**
	 * Dryness read from how much rain actually falls.
	 *
	 * <p>Precipitation rather than humidity, deliberately. Humidity says what the air
	 * is carrying; only rain reaching the ground erodes anything, and a saturated
	 * coastal desert erodes as slowly as a dry one. That is exactly the case the
	 * two-part moisture model exists to distinguish, so throwing it away here by
	 * reading the wrong quantity would waste it.
	 *
	 * @param wetRate rain at or above which the ground counts as fully humid
	 * @param dryRate rain at or below which it counts as fully arid
	 */
	static RegionClimate fromPrecipitation(
			final MoistureField moisture, final double dryRate, final double wetRate) {
		return (worldX, worldZ) -> {
			double rain = moisture.at(worldX, worldZ).precipitation();
			double wetness = (rain - dryRate) / (wetRate - dryRate);
			double clamped = Math.max(0.0, Math.min(1.0, wetness));

			// Eased rather than linear, so most of the world sits clearly at one end
			// and the mixed band between them is narrow. A linear ramp leaves almost
			// everywhere half arid, which reintroduces the patchwork it is here to fix.
			return 1.0 - clamped * clamped * (3.0 - 2.0 * clamped);
		};
	}
}
