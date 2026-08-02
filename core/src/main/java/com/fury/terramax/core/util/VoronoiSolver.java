package com.fury.terramax.core.util;

/**
 * Resolves which plate owns a world position, and how far that position is from
 * the plate's nearest boundary.
 *
 * <p>Infinite and stateless. Any coordinate can be queried directly without
 * generating its surroundings first.
 */
public final class VoronoiSolver {
	/**
	 * Cells searched in each direction around the query.
	 *
	 * <p>A radius of 1 (3x3) is provably enough to find the <em>nearest</em> site,
	 * because {@link PoissonDisk} keeps every point inside its own cell. The
	 * second-nearest can occasionally lie one ring further out, and getting it wrong
	 * would misplace a plate boundary, so this uses 2 (5x5). Boundaries are where
	 * mountains form; a wrong answer here is visible from orbit.
	 */
	private static final int SEARCH_RADIUS_CELLS = 2;

	private final PoissonDisk sites;

	public VoronoiSolver(final PoissonDisk sites) {
		this.sites = sites;
	}

	public PoissonDisk sites() {
		return sites;
	}

	/**
	 * Finds the owning plate and the distance to its nearest boundary.
	 *
	 * <p>Boundary distance is the perpendicular distance to the bisector between the
	 * nearest and second-nearest sites, not the more common {@code d2 - d1}
	 * difference. The difference form is cheaper but is not a distance: it grows at a
	 * rate that depends on the angle between the query and the two sites, so ridges
	 * built from it vary in width for no physical reason. The perpendicular distance
	 * is in blocks and behaves consistently, which matters when a mountain profile is
	 * a function of it.
	 */
	public VoronoiSample sample(final double worldX, final double worldZ) {
		long centreX = sites.cellX(worldX);
		long centreZ = sites.cellZ(worldZ);

		long nearestCellX = 0;
		long nearestCellZ = 0;
		double nearestX = 0.0;
		double nearestZ = 0.0;
		double nearestSq = Double.MAX_VALUE;

		long secondCellX = 0;
		long secondCellZ = 0;
		double secondX = 0.0;
		double secondZ = 0.0;
		double secondSq = Double.MAX_VALUE;

		for (int dz = -SEARCH_RADIUS_CELLS; dz <= SEARCH_RADIUS_CELLS; dz++) {
			for (int dx = -SEARCH_RADIUS_CELLS; dx <= SEARCH_RADIUS_CELLS; dx++) {
				long cellX = centreX + dx;
				long cellZ = centreZ + dz;

				double siteX = sites.pointX(cellX, cellZ);
				double siteZ = sites.pointZ(cellX, cellZ);

				double offsetX = siteX - worldX;
				double offsetZ = siteZ - worldZ;
				double distSq = offsetX * offsetX + offsetZ * offsetZ;

				if (distSq < nearestSq) {
					secondCellX = nearestCellX;
					secondCellZ = nearestCellZ;
					secondX = nearestX;
					secondZ = nearestZ;
					secondSq = nearestSq;

					nearestCellX = cellX;
					nearestCellZ = cellZ;
					nearestX = siteX;
					nearestZ = siteZ;
					nearestSq = distSq;
				} else if (distSq < secondSq) {
					secondCellX = cellX;
					secondCellZ = cellZ;
					secondX = siteX;
					secondZ = siteZ;
					secondSq = distSq;
				}
			}
		}

		double boundaryDistance = perpendicularDistanceToBisector(
				worldX, worldZ, nearestX, nearestZ, secondX, secondZ);

		return new VoronoiSample(
				nearestCellX, nearestCellZ,
				nearestX, nearestZ,
				Math.sqrt(nearestSq),
				secondCellX, secondCellZ,
				boundaryDistance);
	}

	/**
	 * Distance from a query point to the perpendicular bisector of two sites.
	 *
	 * <p>The bisector is the Voronoi edge between them. Its normal is the direction
	 * from one site to the other, and it passes through their midpoint, so the
	 * distance is the projection of {@code query - midpoint} onto that normal.
	 */
	private static double perpendicularDistanceToBisector(
			final double queryX, final double queryZ,
			final double aX, final double aZ,
			final double bX, final double bZ) {
		double axisX = bX - aX;
		double axisZ = bZ - aZ;
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);

		// Coincident sites have no bisector. Impossible while PoissonDisk guarantees
		// a positive minimum separation, but returning 0 keeps this total.
		if (axisLength == 0.0) {
			return 0.0;
		}

		double midX = (aX + bX) * 0.5;
		double midZ = (aZ + bZ) * 0.5;

		return Math.abs((queryX - midX) * axisX + (queryZ - midZ) * axisZ) / axisLength;
	}
}
