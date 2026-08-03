package com.fury.terramax.core.plate;

/**
 * Whether a patch of crust is continental or oceanic.
 *
 * <p>This is a property of a <b>crust cell</b>, not of a plate. Earth's plates
 * carry both: North America and the western Atlantic are one plate, so its coast
 * is a passive margin with no relief and its actual boundary is the Mid-Atlantic
 * ridge two thousand miles offshore. Making crust type a plate property, as the
 * first build did, forces every coastline in the world to be a plate boundary and
 * makes quiet coasts impossible by construction.
 */
public enum CrustType {
	CONTINENTAL,
	OCEANIC;

	public boolean isContinental() {
		return this == CONTINENTAL;
	}
}
