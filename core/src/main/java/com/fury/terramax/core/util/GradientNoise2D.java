package com.fury.terramax.core.util;

/**
 * Single-octave 2D gradient noise, in the Perlin family.
 *
 * <p>Stateless and unbounded. There is no permutation table, so no period and no
 * setup cost: lattice gradients come straight from {@link Hashing}. A coordinate
 * ten million blocks out is as cheap and as valid as the origin, which matters
 * because Terramax has no world centre in any meaningful sense.
 *
 * <p>Output is scaled to approximately {@code [-1, 1]}. Gradient noise with unit
 * gradients peaks near {@code +/-0.707} in 2D, so results are multiplied by
 * {@code sqrt(2)}. Extreme values remain rare, as with any gradient noise.
 */
public final class GradientNoise2D {
	/** Compensates for the natural 1/sqrt(2) peak of 2D gradient noise. */
	private static final double NORMALISATION = Math.sqrt(2.0);

	private static final long SALT_GRADIENT = 31L;

	private final long seed;

	public GradientNoise2D(final long seed) {
		this.seed = seed;
	}

	/** Samples the field. Pure function of seed and position. */
	public double sample(final double x, final double z) {
		long cellX = (long) Math.floor(x);
		long cellZ = (long) Math.floor(z);

		double fracX = x - cellX;
		double fracZ = z - cellZ;

		double weightX = fade(fracX);
		double weightZ = fade(fracZ);

		double corner00 = dotGradient(cellX, cellZ, fracX, fracZ);
		double corner10 = dotGradient(cellX + 1, cellZ, fracX - 1.0, fracZ);
		double corner01 = dotGradient(cellX, cellZ + 1, fracX, fracZ - 1.0);
		double corner11 = dotGradient(cellX + 1, cellZ + 1, fracX - 1.0, fracZ - 1.0);

		double lowerEdge = lerp(corner00, corner10, weightX);
		double upperEdge = lerp(corner01, corner11, weightX);

		return lerp(lowerEdge, upperEdge, weightZ) * NORMALISATION;
	}

	/**
	 * Dot product of a lattice corner's gradient with the offset to the sample.
	 *
	 * <p>The gradient is a unit vector at a hashed angle rather than one of the
	 * usual twelve fixed directions. Picking from a small fixed set leaves faint
	 * axis-aligned structure, which is exactly what domain warping is meant to
	 * remove, so it would be self-defeating here.
	 */
	private double dotGradient(final long cellX, final long cellZ, final double offsetX, final double offsetZ) {
		double angle = Hashing.unitDouble(seed, cellX, cellZ, SALT_GRADIENT) * Math.TAU;

		return Math.cos(angle) * offsetX + Math.sin(angle) * offsetZ;
	}

	/**
	 * Quintic fade, {@code 6t^5 - 15t^4 + 10t^3}.
	 *
	 * <p>Its first and second derivatives are zero at both ends, so cell boundaries
	 * leave no visible creases. The cubic {@code 3t^2 - 2t^3} only zeroes the first
	 * derivative, and the resulting grid shows up once the field drives terrain.
	 */
	private static double fade(final double t) {
		return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
	}

	private static double lerp(final double a, final double b, final double t) {
		return a + t * (b - a);
	}
}
