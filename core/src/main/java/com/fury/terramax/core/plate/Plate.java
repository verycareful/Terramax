package com.fury.terramax.core.plate;

/**
 * One tectonic plate.
 *
 * <p>Purely derived: every field is a pure function of the world seed and the
 * plate's cell coordinate. Nothing is stored or persisted, so two plates with the
 * same coordinates in the same world are always identical.
 *
 * @param cellX         cell column, part of the plate's identity
 * @param cellZ         cell row, part of the plate's identity
 * @param type          continent or ocean floor
 * @param baseElevation mean world Y of this plate's interior
 * @param motionX       x component of plate motion, unit-scaled
 * @param motionZ       z component of plate motion, unit-scaled
 */
public record Plate(
		long cellX,
		long cellZ,
		PlateType type,
		double baseElevation,
		double motionX,
		double motionZ) {

	public boolean isContinental() {
		return type == PlateType.CONTINENTAL;
	}
}
