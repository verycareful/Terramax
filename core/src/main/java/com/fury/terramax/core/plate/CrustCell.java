package com.fury.terramax.core.plate;

/**
 * One cell of the crust lattice: the unit of plate outline granularity and the
 * unit of crust type.
 *
 * @param cellX         lattice column
 * @param cellZ         lattice row
 * @param siteX         world x of the cell's site
 * @param siteZ         world z of the cell's site
 * @param crustType     continental or oceanic, independent of plate membership
 * @param baseElevation this cell's own base height in blocks, before any relief
 */
public record CrustCell(
		long cellX, long cellZ,
		double siteX, double siteZ,
		CrustType crustType,
		double baseElevation) {

	public boolean isContinental() {
		return crustType.isContinental();
	}
}
