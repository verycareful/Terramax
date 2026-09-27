package com.fury.terramax.sim.probe;

import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.terrain.RangeType;
import com.fury.terramax.core.terrain.TectonicHeight;

/**
 * What more than one probe needs.
 *
 * <p>Small on purpose. Anything used by a single probe belongs to that probe; what
 * lands here is what several must agree on, because two probes disagreeing about how
 * to name a margin or where a range stops would make their numbers
 * incomparable.
 */
public final class Probes {
	/**
	 * Samples per axis for the grid sweeps.
	 *
	 * <p>Shared by the census, the relief profile and the search, so all three describe
	 * the same set of columns. A census taken at one resolution and a profile at
	 * another would report shares that do not add up against depths that came from
	 * somewhere else.
	 */
	public static final int GRID = 320;

	private Probes() {
	}

	/**
	 * The generator's own falloff, repeated here so a probe measures what it does.
	 *
	 * <p>Deliberately identical to {@code MountainRidge.domeAt}. A probe that used a
	 * linear taper instead reported a margin envelope of 0.374 where the real figure
	 * was 0.549, which is the sort of error that sends a tuning pass after the wrong
	 * constant. This duplication is the reason the probe layer exists as a layer: one
	 * copy, used by everything that measures ranges.
	 */
	public static double dome(final double distance, final double width) {
		if (distance >= width) {
			return 0.0;
		}

		double t = distance / width;

		return 1.0 - (t * t * (3.0 - 2.0 * t));
	}

	/**
	 * Names the class of margin a sample sits on, at the granularity relief uses.
	 *
	 * <p>Reports the range type, which is a property of the pair of crust cells, and
	 * separately which flank of it the column stands on where that differs. Splitting a
	 * one-sided range into "arc" and "trench" as if they were different kinds of margin
	 * is what hid the discontinuity between them: they are one range, and the question
	 * is which half you are on.
	 */
	public static String marginClass(final TectonicHeight.Sample sample) {
		PlateSample plate = sample.plate();

		// Taken from the sample rather than recomputed. Two types are decided against a
		// region field the plate pair cannot see, so RangeType.of would report a fault
		// block province as an ordinary rift and the census would show a landform that
		// covers whole provinces as not existing at all.
		RangeType type = sample.type();

		if (type == RangeType.SUBDUCTION_ARC) {
			return plate.isOverridingPlate() ? "subduction, arc" : "subduction, trench";
		}

		// Split by crust, because sutures exist only on continental crust and lumping
		// the two together buries them: an average over every plate interior in the
		// world is mostly ocean floor, where the answer is correctly zero.
		if (type == RangeType.FOSSIL_SUTURE) {
			return plate.crust().isContinental()
					? "fossil suture, land"
					: "fossil suture, ocean";
		}

		// Split for the same reason, and by the column's own crust rather than the
		// plume's. The two landforms are nothing alike, one being a chain of islands
		// and the other a swell with a hole in it, and a mean height over both says
		// nothing about either.
		if (type == RangeType.HOTSPOT) {
			return plate.crust().isContinental()
					? "hotspot, swell"
					: "hotspot, chain";
		}

		return type.name().toLowerCase().replace('_', ' ');
	}
}
