package com.fury.terramax.core.util;

/**
 * Fractional Brownian motion: several octaves of {@link GradientNoise2D} summed
 * at rising frequency and falling amplitude.
 *
 * <p>One octave is too smooth to look like anything natural. Stacking octaves is
 * what produces detail at every scale, which is the property real terrain has and
 * plain noise does not.
 *
 * <p>Output is normalised to approximately {@code [-1, 1]} by dividing by the sum
 * of amplitudes, so changing the octave count does not change the field's range.
 */
public final class FractalNoise2D {
	/** Distinguishes each octave's lattice so they do not align at the origin. */
	private static final long OCTAVE_SALT = 0x51_7C_C1_B7_27_22_0A_95L;

	private final GradientNoise2D[] octaves;
	private final double frequency;
	private final double lacunarity;
	private final double persistence;
	private final double amplitudeSum;

	/**
	 * @param seed        world seed
	 * @param octaveCount number of octaves; 1 is smooth, 6 is highly detailed
	 * @param wavelength  blocks per cycle of the lowest-frequency octave
	 * @param lacunarity  frequency multiplier per octave, conventionally 2.0
	 * @param persistence amplitude multiplier per octave, conventionally 0.5
	 */
	public FractalNoise2D(
			final long seed,
			final int octaveCount,
			final double wavelength,
			final double lacunarity,
			final double persistence) {
		if (octaveCount < 1) {
			throw new IllegalArgumentException("octaveCount must be at least 1, got " + octaveCount);
		}

		if (wavelength <= 0.0) {
			throw new IllegalArgumentException("wavelength must be positive, got " + wavelength);
		}

		this.octaves = new GradientNoise2D[octaveCount];
		this.frequency = 1.0 / wavelength;
		this.lacunarity = lacunarity;
		this.persistence = persistence;

		double sum = 0.0;
		double amplitude = 1.0;

		for (int i = 0; i < octaveCount; i++) {
			octaves[i] = new GradientNoise2D(seed + i * OCTAVE_SALT);
			sum += amplitude;
			amplitude *= persistence;
		}

		this.amplitudeSum = sum;
	}

	/** Conventional 2:1 lacunarity and 0.5 persistence. */
	public static FractalNoise2D standard(final long seed, final int octaveCount, final double wavelength) {
		return new FractalNoise2D(seed, octaveCount, wavelength, 2.0, 0.5);
	}

	/** Samples the field, in approximately {@code [-1, 1]}. */
	public double sample(final double x, final double z) {
		double total = 0.0;
		double amplitude = 1.0;
		double currentFrequency = frequency;

		for (GradientNoise2D octave : octaves) {
			total += octave.sample(x * currentFrequency, z * currentFrequency) * amplitude;
			amplitude *= persistence;
			currentFrequency *= lacunarity;
		}

		return total / amplitudeSum;
	}

	/** Samples the field remapped to approximately {@code [0, 1]}. */
	public double sampleUnit(final double x, final double z) {
		return (sample(x, z) + 1.0) * 0.5;
	}
}
