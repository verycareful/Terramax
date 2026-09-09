package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateSample;

/**
 * What kind of range a margin builds.
 *
 * <p>Relief used to be a switch on {@code PlateBoundaryType} crossed with the crust
 * on each side, evaluated fresh at every column. That is enough to decide a peak
 * height and nothing else, so every margin in the world had the same shape: a
 * symmetric dome, tall or short. Real margins differ in <b>profile</b> far more than
 * in height. An Andean margin and a Himalayan one reach the same altitude and look
 * nothing alike, because one has an ocean trench a hundred kilometres from its crest
 * and the other has a foreland basin.
 *
 * <p><b>A range type is a property of the pair of crust cells, never of the query.</b>
 * That is what makes an asymmetric profile possible at all. Given a type and the
 * signed distance from the margin, relief is one continuous function of one variable,
 * so the two flanks cannot disagree about the height where they meet. The previous
 * arrangement, where each side chose its own half of the profile from its own crust
 * type, was discontinuous by construction: an arc side evaluating +900 and a trench
 * side evaluating -80 at the same falloff of 1 left a step of the difference between
 * them along the whole margin.
 *
 * <p><b>Three of the design's seven range types are not here yet.</b> Fossil sutures
 * need interior seams to carry a hashed age, hotspots need a point field that does
 * not hang off plate geometry at all, and fault blocks need a near-periodic
 * across-range function. Each is its own slice. What this covers is the four types
 * that plate boundaries alone can produce, plus the two margins that are not ranges
 * and the seam that is not a boundary.
 */
public enum RangeType {
	/**
	 * Two continents meet and neither will subduct. The tallest ranges there are.
	 *
	 * <p>Symmetric, and knowingly incomplete: the design's collision sequence runs
	 * foreland basin, foothills, middle mountains, wall, plateau behind, which is
	 * asymmetric and needs deposition to build the basin. The frame to express that
	 * exists now; the sequence itself waits on the deposition slice.
	 */
	CONTINENTAL_COLLISION,

	/**
	 * Ocean crust dives under continent. The one-sided range.
	 *
	 * <p>A trench at the contact where the slab goes down, then the coast, then a
	 * wall rising with no runway behind it, then a long inland descent. The
	 * asymmetry is the entire character of the type, and it is expressible only
	 * because the profile reads a signed coordinate oriented by the pair's own crust
	 * types rather than by which side asked.
	 */
	SUBDUCTION_ARC,

	/**
	 * Ocean under ocean. The subduction arc with everything continental deleted.
	 *
	 * <p>Its polarity, which side gets the trench, is hashed from the margin's
	 * identity rather than read off the crust, because both sides are the same kind
	 * of crust and there is nothing to read. Real arc polarity is close to arbitrary
	 * too, so this loses nothing.
	 */
	ISLAND_ARC,

	/**
	 * Continent pulling apart: a floor dropped between two uplifted shoulders.
	 *
	 * <p>The active state of the design's rift shoulder. The floor sits below the
	 * surrounding land and the shoulders stand above it, which is a shape a single
	 * peak height cannot express at all. The passive state, the torn escarpment left
	 * behind once the rift becomes an ocean, is a later slice.
	 */
	CONTINENTAL_RIFT,

	/** Ocean floor spreading. A broad symmetric swell, the most ordinary shape here. */
	OCEANIC_RIDGE,

	/**
	 * Continent and ocean pulling apart, which is to say a quiet coast.
	 *
	 * <p><b>Builds nothing, and that is the point.</b> Under the old rules this case
	 * had no name: a divergent margin asked its own crust type and got a 260-block
	 * ridge from the ocean side and a 110-block rift valley from the land side, so
	 * the largest remaining step in the world sat along coastlines that should have
	 * been the calmest ground in it. The elevation difference across a passive margin
	 * is already carried by the crust base on each side, which the base blend grades.
	 */
	PASSIVE_MARGIN,

	/** Plates sliding past. Real relief, but slight, and with no across-margin structure. */
	TRANSFORM,

	/**
	 * A seam between two cells of the same plate. Not a margin at all, yet.
	 *
	 * <p>These are the fossil sutures of the design, and there are more of them than
	 * anything else: they are 22.7 percent of the world where the nearest margin of
	 * any kind is one of these. They carry no relief until the suture slice gives
	 * them a hashed age.
	 */
	INTERIOR_SEAM;

	/**
	 * Classifies the margin between two crust cells.
	 *
	 * <p>Reads only pair-symmetric questions. {@code isContinentalCollision},
	 * {@code isSubducting} and the crust types of the two halves are all properties
	 * of the pair, so this returns the same type from either side and from every
	 * position near the margin.
	 */
	public static RangeType of(final PlateSample margin) {
		return switch (margin.boundaryType()) {
			case CONVERGENT -> margin.isContinentalCollision()
					? CONTINENTAL_COLLISION
					: margin.isSubducting() ? SUBDUCTION_ARC : ISLAND_ARC;
			case DIVERGENT -> divergent(margin);
			case TRANSFORM -> TRANSFORM;
			case NONE -> INTERIOR_SEAM;
		};
	}

	private static RangeType divergent(final PlateSample margin) {
		if (margin.lowCrust().isContinental() != margin.highCrust().isContinental()) {
			return PASSIVE_MARGIN;
		}

		return margin.lowCrust().isContinental() ? CONTINENTAL_RIFT : OCEANIC_RIDGE;
	}

	/** True where the type's profile is a mirror image about the margin. */
	public boolean isSymmetric() {
		return this != SUBDUCTION_ARC && this != ISLAND_ARC;
	}
}
