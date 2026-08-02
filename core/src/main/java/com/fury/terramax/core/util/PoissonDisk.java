package com.fury.terramax.core.util;

/**
 * Scatters one point per grid cell, deterministically and without bounds.
 *
 * <p><b>On the name.</b> This is a jittered grid, not Bridson's Poisson disk
 * algorithm. Bridson's produces better-separated points but requires generating a
 * region in sequence, with each sample depending on those already placed. That is
 * incompatible with what this needs to be: infinite, and queryable at any
 * coordinate in constant time without generating anything around it. A chunk at
 * x=8,000,000 must resolve its plate without first sampling the eight million
 * blocks leading up to it.
 *
 * <p>A jittered grid is the standard tileable approximation. Each cell holds
 * exactly one point, offset from the cell centre by a bounded random amount. With
 * jitter clamped below 0.5 the minimum separation between neighbouring points has
 * a hard floor of {@code spacing * (1 - 2 * jitter)}, which is the property
 * Poisson disk sampling is usually wanted for.
 *
 * <p>All methods are pure functions of the seed and the cell coordinate. Nothing
 * is cached here; caching belongs to the caller.
 */
public final class PoissonDisk {
	/** Distinguishes the x and z jitter draws for the same cell. */
	private static final long SALT_X = 1L;
	private static final long SALT_Z = 2L;

	/**
	 * Above 0.5 a point can cross into a neighbouring cell, which breaks both the
	 * separation guarantee and the assumption that a 3x3 cell search finds the
	 * nearest point.
	 */
	private static final double MAX_JITTER = 0.5;

	private final long seed;
	private final double spacing;
	private final double jitter;

	/**
	 * @param seed    world seed
	 * @param spacing cell size in blocks, and the mean distance between points
	 * @param jitter  offset from cell centre as a fraction of spacing, in [0, 0.5]
	 */
	public PoissonDisk(final long seed, final double spacing, final double jitter) {
		if (spacing <= 0.0) {
			throw new IllegalArgumentException("spacing must be positive, got " + spacing);
		}

		if (jitter < 0.0 || jitter > MAX_JITTER) {
			throw new IllegalArgumentException(
					"jitter must be in [0, " + MAX_JITTER + "], got " + jitter
							+ ". Above " + MAX_JITTER + " points escape their cell and the"
							+ " 3x3 nearest-point search stops being correct.");
		}

		this.seed = seed;
		this.spacing = spacing;
		this.jitter = jitter;
	}

	public double spacing() {
		return spacing;
	}

	/** The world x of the point belonging to the given cell. */
	public double pointX(final long cellX, final long cellZ) {
		double offset = (Hashing.unitDouble(seed, cellX, cellZ, SALT_X) - 0.5) * 2.0 * jitter;
		return (cellX + 0.5 + offset) * spacing;
	}

	/** The world z of the point belonging to the given cell. */
	public double pointZ(final long cellX, final long cellZ) {
		double offset = (Hashing.unitDouble(seed, cellX, cellZ, SALT_Z) - 0.5) * 2.0 * jitter;
		return (cellZ + 0.5 + offset) * spacing;
	}

	/** The cell column containing the given world x. */
	public long cellX(final double worldX) {
		return Math.floorDiv((long) Math.floor(worldX), (long) spacing);
	}

	/** The cell row containing the given world z. */
	public long cellZ(final double worldZ) {
		return Math.floorDiv((long) Math.floor(worldZ), (long) spacing);
	}

	/**
	 * The guaranteed minimum distance between any two points.
	 *
	 * <p>Two points in adjacent cells are closest when both sit at the extreme of
	 * their jitter range, facing each other. They are then {@code spacing} apart
	 * less {@code 2 * jitter * spacing}.
	 */
	public double minimumSeparation() {
		return spacing * (1.0 - 2.0 * jitter);
	}
}
