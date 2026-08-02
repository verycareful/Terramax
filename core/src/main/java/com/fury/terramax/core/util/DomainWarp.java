package com.fury.terramax.core.util;

/**
 * Displaces sample coordinates by a noise field before they are used.
 *
 * <p>Voronoi cells are convex polygons with straight edges. Real plates are
 * nothing of the sort. Warping the input coordinates bends those straight edges
 * into sinuous ones without changing the underlying cell structure, so plates keep
 * their identities, their neighbours and their areas while ceasing to look
 * geometric.
 *
 * <p>Two independent noise fields drive the x and z displacement. Using one field
 * for both would shift every point along the same diagonal, which shears the map
 * rather than warping it.
 *
 * <p><b>Cost.</b> Distances measured after warping are distances in warped space.
 * While displacement is small relative to feature size the difference is
 * negligible, but a very strong warp will stretch and compress boundary distances,
 * and anything derived from them, unevenly.
 */
public final class DomainWarp {
	/** Separates the two displacement fields so x and z do not correlate. */
	private static final long SALT_X = 0x2545F4914F6CDD1DL;
	private static final long SALT_Z = 0x3C79AC492BA7B653L;

	private final FractalNoise2D displacementX;
	private final FractalNoise2D displacementZ;
	private final double strengthBlocks;

	/**
	 * @param seed           world seed
	 * @param strengthBlocks maximum displacement in blocks
	 * @param wavelength     blocks per cycle of the displacement field
	 * @param octaves        detail in the displacement; 2 to 3 is usually enough
	 */
	public DomainWarp(
			final long seed,
			final double strengthBlocks,
			final double wavelength,
			final int octaves) {
		this.displacementX = FractalNoise2D.standard(seed ^ SALT_X, octaves, wavelength);
		this.displacementZ = FractalNoise2D.standard(seed ^ SALT_Z, octaves, wavelength);
		this.strengthBlocks = strengthBlocks;
	}

	public double warpX(final double x, final double z) {
		return x + displacementX.sample(x, z) * strengthBlocks;
	}

	public double warpZ(final double x, final double z) {
		return z + displacementZ.sample(x, z) * strengthBlocks;
	}

	public double strengthBlocks() {
		return strengthBlocks;
	}
}
