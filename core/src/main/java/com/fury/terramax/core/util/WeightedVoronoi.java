package com.fury.terramax.core.util;

/**
 * Nearest-site lookup where each site carries a weight that extends its reach.
 *
 * <p>Ordinary Voronoi gives every site a cell of roughly the same size, which is
 * wrong for plates: Earth has one Eurasia and a dozen microplates. Subtracting a
 * per-site weight from the distance lets a strong site capture ground far outside
 * its own lattice cell, and lets a weak site be swallowed entirely and yield
 * nothing at all. This is the additively weighted, or Apollonius, diagram.
 *
 * <p><b>Weights are heavy-tailed on purpose.</b> A uniform draw would make every
 * site somewhat larger or smaller than average, which is variation without
 * character. Raising a uniform value to a power pushes most sites near zero and
 * leaves a few near the maximum, which is the distribution that produces a
 * continent beside a microplate.
 *
 * <p><b>The search radius has to grow with the weight.</b> A radius of 2 is
 * sufficient for unweighted sites because every point stays in its own cell. Once
 * a site can win from {@code maxWeightBlocks} further away, the search must reach
 * that far or a large plate will be clipped into a square wherever the search
 * stopped. That clipping is invisible at a glance and obvious once you look for
 * it, so the radius is derived rather than chosen.
 */
public final class WeightedVoronoi {
	/** Separates the weight draw from every other hash on the same cell. */
	private static final long SALT_WEIGHT = 0x2545F4914F6CDD1DL;

	/**
	 * Exponent applied to the uniform weight draw. Above 1 skews toward zero, so
	 * most sites are ordinary and a few are enormous. At 3.0 roughly one site in
	 * eight exceeds half the maximum weight.
	 */
	private static final double WEIGHT_SKEW = 3.0;

	/** Base search radius, sufficient when all weights are zero. */
	private static final int BASE_SEARCH_RADIUS_CELLS = 2;

	private final PoissonDisk sites;
	private final long seed;
	private final double maxWeightBlocks;
	private final int searchRadiusCells;

	/**
	 * @param sites           lattice supplying candidate site positions
	 * @param seed            world seed
	 * @param maxWeightBlocks largest reach bonus any site may receive, in blocks
	 */
	public WeightedVoronoi(final PoissonDisk sites, final long seed, final double maxWeightBlocks) {
		if (maxWeightBlocks < 0.0) {
			throw new IllegalArgumentException(
					"maxWeightBlocks must not be negative, got " + maxWeightBlocks);
		}

		this.sites = sites;
		this.seed = seed;
		this.maxWeightBlocks = maxWeightBlocks;
		this.searchRadiusCells = BASE_SEARCH_RADIUS_CELLS
				+ (int) Math.ceil(maxWeightBlocks / sites.spacing());
	}

	public PoissonDisk sites() {
		return sites;
	}

	/** Cells searched in each direction. Grows with the weight range. */
	public int searchRadiusCells() {
		return searchRadiusCells;
	}

	/** This site's reach bonus in blocks, in [0, maxWeightBlocks]. */
	public double weightOf(final long cellX, final long cellZ) {
		double uniform = Hashing.unitDouble(seed, cellX, cellZ, SALT_WEIGHT);

		return maxWeightBlocks * Math.pow(uniform, WEIGHT_SKEW);
	}

	/** The site whose weighted distance to the query is smallest. */
	public Nearest nearest(final double worldX, final double worldZ) {
		long centreX = sites.cellX(worldX);
		long centreZ = sites.cellZ(worldZ);

		long bestCellX = centreX;
		long bestCellZ = centreZ;
		double bestSiteX = 0.0;
		double bestSiteZ = 0.0;
		double best = Double.MAX_VALUE;

		for (int dz = -searchRadiusCells; dz <= searchRadiusCells; dz++) {
			for (int dx = -searchRadiusCells; dx <= searchRadiusCells; dx++) {
				long cellX = centreX + dx;
				long cellZ = centreZ + dz;

				double siteX = sites.pointX(cellX, cellZ);
				double siteZ = sites.pointZ(cellX, cellZ);

				double offsetX = siteX - worldX;
				double offsetZ = siteZ - worldZ;

				// Weighted distance, not squared distance. Subtracting a weight from a
				// squared distance would be a power diagram, whose cells are bounded by
				// straight lines but whose weights scale as area rather than length,
				// making them far harder to reason about in blocks.
				double weighted = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ)
						- weightOf(cellX, cellZ);

				if (weighted < best) {
					best = weighted;
					bestCellX = cellX;
					bestCellZ = cellZ;
					bestSiteX = siteX;
					bestSiteZ = siteZ;
				}
			}
		}

		return new Nearest(bestCellX, bestCellZ, bestSiteX, bestSiteZ);
	}

	/** The winning site and its identity. */
	public record Nearest(long cellX, long cellZ, double siteX, double siteZ) {
	}
}
