package com.fury.terramax.core.plate;

/**
 * Everything the terrain functions need to know about one world position.
 *
 * @param plate             the plate that owns this position
 * @param neighbour         the plate across the nearest boundary
 * @param boundaryType      what those two plates are doing to each other
 * @param boundaryDistance  perpendicular distance to that boundary, in blocks
 * @param convergence       signed closing rate along the boundary normal; positive
 *                          means converging, negative diverging
 * @param shear             magnitude of sliding motion along the boundary
 */
public record PlateSample(
		Plate plate,
		Plate neighbour,
		PlateBoundaryType boundaryType,
		double boundaryDistance,
		double convergence,
		double shear) {

	/**
	 * True where an oceanic plate is being driven under a continental one.
	 *
	 * <p>The two sides of a subduction zone are not symmetric: the continental side
	 * rises into a mountain arc, the oceanic side drops into a trench. Terrain
	 * functions need to know which side they are standing on.
	 */
	public boolean isSubducting() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& plate.type() != neighbour.type();
	}

	/** True on the overriding (continental) side of a subduction zone. */
	public boolean isOverridingPlate() {
		return isSubducting() && plate.isContinental();
	}

	/** True where two continental plates collide, which is how the largest ranges form. */
	public boolean isContinentalCollision() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& plate.isContinental()
				&& neighbour.isContinental();
	}
}
