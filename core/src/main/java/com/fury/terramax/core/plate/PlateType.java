package com.fury.terramax.core.plate;

/**
 * Whether a plate carries continent or ocean floor.
 *
 * <p>This is the single largest determinant of a plate's base elevation, and it
 * decides what happens when two plates collide: continental against continental
 * throws up mountains, oceanic against continental subducts.
 */
public enum PlateType {
	CONTINENTAL,
	OCEANIC
}
