package com.fury.terramax.core.climate;

import com.fury.terramax.core.terrain.HeightField;

/**
 * Prevailing wind anywhere in the world.
 *
 * <p>A first-class field rather than a helper for moisture. It is queryable at any
 * coordinate, renderable on its own, and intended to carry weather later: storms form
 * where air converges, and convergence is this field's divergence with the sign
 * flipped, so a storm map is a derived layer rather than a new system.
 *
 * <p>Two terms. A zonal base flow that depends only on latitude, and a deflection by
 * terrain.
 *
 * <p><b>The base flow is one sine, and its zeros do the work.</b> Taking the eastward
 * component as {@code -sin(3 * PI * latitude)} gives easterly trades, then
 * westerlies, then polar easterlies, with the sign reversing twice between equator and
 * pole. What makes it the right function rather than merely a convenient one is where
 * it reaches zero: at the equator, at one third, and at two thirds of the way to the
 * pole. Those are the doldrums, the horse latitudes and the polar front, the three
 * calm belts on Earth, and every major desert on the planet sits under the middle one.
 * None of that had to be placed.
 *
 * <p><b>Deflection reads uplift, never finished terrain.</b> Wind is shaped by
 * terrain, moisture rides wind, river discharge depends on moisture, and terrain will
 * depend on rivers once drainage exists. That is a closed loop, and it stays solvable
 * only because this reads the uplift layer, which is computed before any of it. It is
 * also the physically correct choice: a 30-block hillock must not deflect a
 * continental airstream, and finished terrain would make it try.
 */
public final class WindField {
	/** Bands per quarter turn: trades, westerlies, polar easterlies. */
	private static final double BANDS = 3.0;

	/**
	 * Meridional flow as a fraction of zonal.
	 *
	 * <p>Air does move poleward and equatorward within each cell, but far less than it
	 * moves east and west. At 1.0 every wind on the planet would blow at 45 degrees.
	 */
	private static final double MERIDIONAL_FRACTION = 0.35;

	/**
	 * How much of the uphill component is removed, in [0, 1].
	 *
	 * <p>Not 1.0: air does climb over mountains, it does not only flow around them.
	 * At 0.75 most of a range's blocking is felt, so flow curves around it and funnels
	 * through gaps, while enough survives to carry moisture over the crest and produce
	 * orographic rain on the windward face.
	 */
	private static final double DEFLECTION_STRENGTH = 0.75;

	/**
	 * Separation used to measure the uplift gradient, in blocks.
	 *
	 * <p>Deliberately coarse, and the value matters. At 900 blocks this picked up
	 * region relief, which varies over 2,300 blocks, so the deflection came out as
	 * noise following every hill field rather than as flow curving around ranges. At
	 * 3,000, about half a range width, a range still registers strongly while a
	 * region-scale bump largely cancels between the two samples.
	 *
	 * <p>The principle: a continental airstream responds to features thousands of
	 * blocks across, not to hillsides.
	 */
	private static final double GRADIENT_STEP_BLOCKS = 3_000.0;

	/** Uplift rise over {@link #GRADIENT_STEP_BLOCKS} that fully deflects the flow. */
	private static final double FULL_DEFLECTION_RISE = 400.0;

	private final ClimateSettings settings;
	private final HeightField uplift;

	/**
	 * @param uplift the large-scale terrain component, <em>not</em> the finished
	 *               surface: see the class note on why that distinction is
	 *               load-bearing
	 */
	public WindField(final ClimateSettings settings, final HeightField uplift) {
		this.settings = settings;
		this.uplift = uplift;
	}

	/**
	 * Direction of increasing latitude at a world Z, as +1 or -1 in world Z.
	 *
	 * <p>Latitude is a triangle wave so the world repeats without a seam, which means
	 * the pole is toward +Z in some bands and toward -Z in others. Meridional flow has
	 * to follow that or the trade winds would blow the wrong way in every other band.
	 */
	public double poleDirection(final double worldZ) {
		double phase = (Math.abs(worldZ) / settings.bandHeightBlocks()) % 2.0;

		return Math.signum(worldZ) * (phase <= 1.0 ? 1.0 : -1.0);
	}

	/** The zonal circulation alone, before terrain has any say. */
	public Wind baseFlow(final double worldZ, final double latitude) {
		double zonal = -Math.sin(BANDS * Math.PI * latitude);

		// Surface flow runs equatorward in the trades and the polar easterlies, and
		// poleward in the westerlies. That is the same alternation as the zonal
		// component, so it shares the sine rather than needing its own.
		double meridional = zonal * MERIDIONAL_FRACTION * poleDirection(worldZ);

		return new Wind(zonal, meridional);
	}

	/** Prevailing wind at a position, after terrain has deflected it. */
	public Wind at(final double worldX, final double worldZ, final double latitude) {
		Wind base = baseFlow(worldZ, latitude);

		double eastRise = uplift.heightAt(worldX + GRADIENT_STEP_BLOCKS, worldZ)
				- uplift.heightAt(worldX - GRADIENT_STEP_BLOCKS, worldZ);
		double southRise = uplift.heightAt(worldX, worldZ + GRADIENT_STEP_BLOCKS)
				- uplift.heightAt(worldX, worldZ - GRADIENT_STEP_BLOCKS);

		double slope = Math.hypot(eastRise, southRise);

		if (slope == 0.0) {
			return base;
		}

		double normalX = eastRise / slope;
		double normalZ = southRise / slope;

		// How much this ground blocks the flow, saturating so a cliff and a wall
		// behave the same rather than the wall inverting the wind.
		double blocking = DEFLECTION_STRENGTH
				* Math.min(1.0, slope / (2.0 * FULL_DEFLECTION_RISE));

		// Only flow heading uphill is blocked. Air running downhill accelerates
		// instead, which is what a katabatic wind is.
		double uphill = base.eastward() * normalX + base.southward() * normalZ;

		if (uphill <= 0.0) {
			return base;
		}

		return new Wind(
				base.eastward() - blocking * uphill * normalX,
				base.southward() - blocking * uphill * normalZ);
	}
}
