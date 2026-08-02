package com.fury.terramax.core.util;

/**
 * Deterministic hashing for world generation.
 *
 * <p>Every value produced here is a pure function of its inputs, with no state
 * and no {@link java.util.Random}. Terrain must be reproducible from a seed and
 * a coordinate alone: the same position must yield the same result whether it is
 * queried by the chunk generator, by the simulator, or twice in a row.
 */
public final class Hashing {
	/** SplitMix64 finalizer constants. */
	private static final long MIX_A = 0xBF58476D1CE4E5B9L;
	private static final long MIX_B = 0x94D049BB133111EBL;

	/** Odd 64-bit constants used to fold coordinates into the seed. */
	private static final long COORD_X = 0x9E3779B97F4A7C15L;
	private static final long COORD_Z = 0xC2B2AE3D27D4EB4FL;
	private static final long SALT = 0x165667B19E3779F9L;

	/** 2^-53. Scales a 53-bit mantissa into [0, 1). */
	private static final double UNIT_SCALE = 0x1.0p-53;

	private Hashing() {
	}

	/**
	 * Mixes a seed and a 2D integer coordinate into a well-distributed 64-bit value.
	 *
	 * <p>Coordinates are folded in by multiplication with distinct odd constants so
	 * that {@code (x, z)} and {@code (z, x)} do not collide, then passed through the
	 * SplitMix64 finalizer to avalanche the bits.
	 */
	public static long hash(final long seed, final long x, final long z) {
		long h = seed ^ SALT;
		h ^= x * COORD_X;
		h ^= z * COORD_Z;

		h ^= h >>> 30;
		h *= MIX_A;
		h ^= h >>> 27;
		h *= MIX_B;
		h ^= h >>> 31;

		return h;
	}

	/** As {@link #hash(long, long, long)}, with an extra salt to decorrelate uses. */
	public static long hash(final long seed, final long x, final long z, final long salt) {
		return hash(seed ^ (salt * COORD_X), x, z);
	}

	/**
	 * A uniformly distributed double in {@code [0, 1)} for the given coordinate.
	 *
	 * <p>Uses the top 53 bits, which is where the avalanche is strongest and what a
	 * double's mantissa can represent exactly.
	 */
	public static double unitDouble(final long seed, final long x, final long z, final long salt) {
		return (hash(seed, x, z, salt) >>> 11) * UNIT_SCALE;
	}
}
