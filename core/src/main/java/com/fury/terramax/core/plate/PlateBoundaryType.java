package com.fury.terramax.core.plate;

/**
 * What two plates are doing to each other where they meet.
 *
 * <p>Derived from relative motion rather than assigned at random, so a boundary
 * classifies identically from either side and a plate's boundaries stay
 * consistent with its own direction of travel.
 */
public enum PlateBoundaryType {
	/** Moving together. Mountains, or a trench where one plate subducts. */
	CONVERGENT,

	/** Moving apart. Rift valleys on land, ridges on the ocean floor. */
	DIVERGENT,

	/** Sliding past. Little vertical relief, but sharp lateral offsets. */
	TRANSFORM,

	/**
	 * Not a boundary. A seam between two crust cells belonging to the same plate.
	 *
	 * <p>Needed because crust cells are far smaller than plates, so most of the
	 * world is interior and the nearest cell seam is usually not a plate boundary at
	 * all. Folding those into {@code TRANSFORM} because they happen to build no
	 * relief made 89% of the world read as transform margin, which is both false and
	 * hides the real distribution.
	 *
	 * <p>In the full design these seams are fossil sutures carrying worn relief
	 * scaled by a hashed age. Here they carry nothing.
	 */
	NONE
}
