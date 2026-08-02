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
	TRANSFORM
}
