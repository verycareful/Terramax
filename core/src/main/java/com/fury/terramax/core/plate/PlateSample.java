package com.fury.terramax.core.plate;

/**
 * What the plate system knows about one world position.
 *
 * <p>Carries both the plate, which supplies motion and therefore boundary
 * behaviour, and the crust cell, which supplies crust type and base elevation.
 * They are separate because a plate holds many cells of both kinds.
 *
 * <p>{@code neighbourCrust} is the crust immediately across the boundary rather
 * than the neighbouring plate's type, because a plate no longer has one type. A
 * subduction zone is defined by ocean meeting continent at a specific place, not
 * by the average composition of two plates.
 *
 * @param plate            plate owning this position
 * @param neighbour        plate across the nearest boundary
 * @param crust            crust cell this position sits in
 * @param neighbourCrust   crust cell immediately across the boundary
 * @param boundaryType     what the two plates are doing to each other
 * @param boundaryDistance blocks to the nearest plate boundary
 * @param convergence      closing speed along the boundary normal; positive closes
 * @param shear            sliding speed along the boundary
 */
public record PlateSample(
		Plate plate,
		Plate neighbour,
		CrustCell crust,
		CrustCell neighbourCrust,
		PlateBoundaryType boundaryType,
		double boundaryDistance,
		double convergence,
		double shear) {

	/** True where oceanic crust is being driven under continental. */
	public boolean isSubducting() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& crust.isContinental() != neighbourCrust.isContinental();
	}

	/** True where two continental masses collide, which builds the tallest ranges. */
	public boolean isContinentalCollision() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& crust.isContinental() && neighbourCrust.isContinental();
	}

	/** True on the upper plate of a subduction zone, which gains an arc rather than a trench. */
	public boolean isOverridingPlate() {
		return isSubducting() && crust.isContinental();
	}
}
