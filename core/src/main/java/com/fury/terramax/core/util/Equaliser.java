package com.fury.terramax.core.util;

import java.util.Arrays;

/**
 * Turns a fractal field into one spread evenly over [0, 1].
 *
 * <p><b>Fractal noise is not uniform, and treating it as though it were has already
 * cost this project twice.</b> Summed octaves pile up near the middle of their range,
 * because that is what adding independent things does. The first time, thresholding it
 * at 0.62 to get 62 percent land produced 78 percent. The second time, using it to pick
 * a region's type from a weighted table gave 30 percent rolling hills and zero
 * inselberg plains: everything landed in the middle of the table and the two ends were
 * unreachable.
 *
 * <p>The fix in both cases is the same, so it lives here once. Sample the field, sort
 * the samples, and read a value's rank among them. Rank is uniform by construction, for
 * any distribution, without anyone having to know what shape the field has.
 *
 * <p><b>Deterministic, and that matters.</b> The sample positions come from the seed,
 * so the same seed and settings always calibrate identically and the mod and the
 * simulator agree about what the world contains. Calibration is a few thousand noise
 * evaluations once, at construction.
 */
public final class Equaliser {
	/** Samples taken to build the table. Enough for a stable quantile, cheap once. */
	private static final int SAMPLES = 4096;

	private static final long SALT_X = 0x9E3779B97F4A7C15L;
	private static final long SALT_Z = 0xC2B2AE3D27D4EB4FL;

	private final double[] sorted;

	private Equaliser(final double[] sorted) {
		this.sorted = sorted;
	}

	/**
	 * Measures a field's distribution by sampling it over a wide area.
	 *
	 * @param spanBlocks area sampled across; should span many wavelengths of the
	 *                   field, or the table describes one hill rather than the field
	 */
	public static Equaliser calibrate(
			final FractalNoise2D noise, final long seed, final double spanBlocks) {
		double[] samples = new double[SAMPLES];

		for (int i = 0; i < SAMPLES; i++) {
			// Scattered rather than gridded. A grid at some multiple of the field's
			// own wavelength samples the same phase every time and reports a
			// distribution far narrower than the truth.
			double x = (Hashing.unitDouble(seed, i, 0L, SALT_X) - 0.5) * spanBlocks;
			double z = (Hashing.unitDouble(seed, i, 0L, SALT_Z) - 0.5) * spanBlocks;

			samples[i] = noise.sample(x, z);
		}

		Arrays.sort(samples);

		return new Equaliser(samples);
	}

	/**
	 * The share of the field lying below this value, in [0, 1].
	 *
	 * <p>Interpolated between neighbouring samples rather than stepped, so the result
	 * is continuous. A stepped version would quantise the field into 4,096 levels,
	 * which is invisible in a threshold and very visible in a height.
	 */
	public double uniform(final double value) {
		int low = 0;
		int high = sorted.length;

		while (low < high) {
			int mid = (low + high) >>> 1;

			if (sorted[mid] < value) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}

		if (low <= 0) {
			return 0.0;
		}

		if (low >= sorted.length) {
			return 1.0;
		}

		double below = sorted[low - 1];
		double above = sorted[low];
		double span = above - below;
		double within = span <= 0.0 ? 0.0 : (value - below) / span;

		return (low - 1 + within) / (sorted.length - 1);
	}
}
