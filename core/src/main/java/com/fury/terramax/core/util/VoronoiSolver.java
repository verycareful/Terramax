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
	 * Cells searched in each direction around a query.
	 *
	 * <p>Exposed so callers running their own search over the same neighbourhood
	 * cannot silently disagree with this one about how far it reaches.
	 */
	public int searchRadiusCells() {
		return SEARCH_RADIUS_CELLS;
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
	 *
	 * <p><b>The two boundary coordinates form a local frame.</b>
	 * {@code boundaryDistance} is the across-boundary axis and {@code alongBoundary}
	 * is the parallel one, both in blocks. Together they let a consumer sample noise
	 * anisotropically: stretched along a range and compressed across it, which is
	 * what turns a smooth swell into parallel ridges. Without the along coordinate
	 * the only available structure is isotropic, and a directional envelope times a
	 * directionless noise is a directional blob with random lumps on it.
	 *
	 * <p>The frame is only meaningful near the boundary it belongs to, and it changes
	 * discontinuously where the second-nearest site changes. Those points are triple
	 * junctions, where real ranges also go structurally incoherent.
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

		// Order the pair canonically before building the frame. Taking it as
		// nearest-then-second would flip the axis when the same boundary is queried
		// from the far side, which mirrors the along-coordinate and would tear any
		// grain built on it straight down the crest of every range.
		boolean flip = nearestCellX > secondCellX
				|| (nearestCellX == secondCellX && nearestCellZ > secondCellZ);

		double aX = flip ? secondX : nearestX;
		double aZ = flip ? secondZ : nearestZ;
		double bX = flip ? nearestX : secondX;
		double bZ = flip ? nearestZ : secondZ;

		double axisX = bX - aX;
		double axisZ = bZ - aZ;
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);

		double boundaryDistance = 0.0;
		double alongBoundary = 0.0;

		// Coincident sites have no bisector. Impossible while PoissonDisk guarantees a
		// positive minimum separation, but leaving both at zero keeps this total.
		if (axisLength > 0.0) {
			double offsetX = worldX - (aX + bX) * 0.5;
			double offsetZ = worldZ - (aZ + bZ) * 0.5;

			boundaryDistance = Math.abs(offsetX * axisX + offsetZ * axisZ) / axisLength;
			alongBoundary = (offsetX * -axisZ + offsetZ * axisX) / axisLength;
		}

		return new VoronoiSample(
				nearestCellX, nearestCellZ,
				nearestX, nearestZ,
				Math.sqrt(nearestSq),
				secondCellX, secondCellZ,
				boundaryDistance,
				alongBoundary);
	}

}
