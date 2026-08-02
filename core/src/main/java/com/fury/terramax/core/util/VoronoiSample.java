package com.fury.terramax.core.util;

/**
 * The result of a Voronoi lookup at one world position.
 *
 * @param cellX             cell column of the owning site; with {@code cellZ}, the plate identity
 * @param cellZ             cell row of the owning site
 * @param siteX             world x of the owning site
 * @param siteZ             world z of the owning site
 * @param distanceToSite    distance from the query point to its owning site
 * @param neighbourCellX    cell column of the second-nearest site, across the nearest boundary
 * @param neighbourCellZ    cell row of the second-nearest site
 * @param boundaryDistance  perpendicular distance to the boundary with that neighbour, always >= 0
 */
public record VoronoiSample(
		long cellX,
		long cellZ,
		double siteX,
		double siteZ,
		double distanceToSite,
		long neighbourCellX,
		long neighbourCellZ,
		double boundaryDistance) {

	/** A stable identity for the owning cell, suitable for hashing plate properties. */
	public long cellId(final long seed) {
		return Hashing.hash(seed, cellX, cellZ);
	}

	/** A stable identity for the boundary itself, independent of which side is queried. */
	public long boundaryId(final long seed) {
		long lowX = Math.min(cellX, neighbourCellX);
		long lowZ = Math.min(cellZ, neighbourCellZ);
		long highX = Math.max(cellX, neighbourCellX);
		long highZ = Math.max(cellZ, neighbourCellZ);

		return Hashing.hash(Hashing.hash(seed, lowX, lowZ), highX, highZ);
	}
}
