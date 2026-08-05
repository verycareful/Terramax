package com.fury.terramax.core.region;

/**
 * One region: a coherent area of a single terrain type, with its own height.
 *
 * <p>Height is drawn within the type's band rather than fixed by the type, so two
 * plateaus are not the same plateau. That is what makes a type a shape rather than
 * a specific landform, and it is why the type list can stay short.
 *
 * @param cellX           region lattice column
 * @param cellZ           region lattice row
 * @param type            terrain type
 * @param targetHeight    this region's own elevation, within the type's band
 * @param reliefAmplitude vertical spread within this region, in blocks
 */
public record Region(
		long cellX, long cellZ,
		RegionType type,
		double targetHeight,
		double reliefAmplitude) {
}
