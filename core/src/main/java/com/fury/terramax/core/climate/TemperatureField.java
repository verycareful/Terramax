package com.fury.terramax.core.climate;

import com.fury.terramax.core.util.FractalNoise2D;

/**
 * Temperature anywhere in the world, in degrees C.
 *
 * <p>Two terms and nothing else. A zonal average that depends only on latitude, and a
 * lapse rate that subtracts altitude. A little noise breaks up the banding.
 *
 * <p><b>Treeline and snowline are not parameters here, and must never become them.</b>
 * Treeline sits almost everywhere on Earth at the altitude where growing-season mean
 * temperature reaches about 6°C. It appears to move because the sea-level starting
 * temperature differs, not because the threshold does: in the tropics that altitude is
 * 3,500 m up, in Patagonia it is the shoreline. Give temperature a lapse rate and the
 * treeline is wherever this field crosses the threshold, correct at every latitude on
 * every mountain with nothing tuned per range. Snowline is the same field at a colder
 * threshold, and the whole stack of montane forest, subalpine scrub, alpine meadow,
 * bare rock and permanent snow is bands of one number.
 *
 * <p><b>Latitude repeats rather than clamping.</b> The world is infinite; walking north
 * forever should bring you back through the tropics rather than into an endless ice
 * sheet. A triangle wave in Z gives that with no seam, because it is continuous at both
 * the pole and the equator where it turns around.
 */
public final class TemperatureField {
	/** Separates the temperature noise from every other field. */
	private static final long SALT_TEMPERATURE = 0xA24BAED4963EE407L;

	/** Octaves in the local departure. Few: weather is not fractal at this scale. */
	private static final int NOISE_OCTAVES = 3;

	/** Growing-season mean at which trees stop. Constant across the planet. */
	public static final double TREELINE_CELSIUS = 6.0;

	/** Below this, snow persists year round. */
	public static final double SNOWLINE_CELSIUS = -4.0;

	private final ClimateSettings settings;
	private final FractalNoise2D noise;

	public TemperatureField(final long seed, final ClimateSettings settings) {
		this.settings = settings;
		this.noise = FractalNoise2D.standard(
				seed ^ SALT_TEMPERATURE, NOISE_OCTAVES, settings.noiseWavelength());
	}

	/**
	 * Latitude at a world Z, as 0 at the equator rising to 1 at the pole.
	 *
	 * <p>A triangle wave, so the pattern repeats north and south without a
	 * discontinuity at either turning point.
	 */
	public double latitude(final double worldZ) {
		double cycle = Math.abs(worldZ) / settings.bandHeightBlocks();
		double phase = cycle % 2.0;

		return phase <= 1.0 ? phase : 2.0 - phase;
	}

	/** Temperature at sea level for this latitude, ignoring altitude and noise. */
	public double zonalTemperature(final double worldZ) {
		return settings.equatorTemperature()
				+ (settings.poleTemperature() - settings.equatorTemperature()) * latitude(worldZ);
	}

	/** Temperature in degrees C at a position and altitude. */
	public double at(final double worldX, final double worldZ, final double height) {
		double local = noise.sample(worldX, worldZ) * settings.seasonalNoiseScale();

		// Altitude only cools above sea level. Below it the ground is under water,
		// which does not follow an atmospheric lapse rate.
		double altitude = Math.max(0.0, height);

		return zonalTemperature(worldZ) + local - altitude * settings.lapseRatePerBlock();
	}

	/**
	 * Altitude at which this column crosses a temperature threshold, in blocks.
	 *
	 * <p>Used to place treeline and snowline as contours. Returns a value below sea
	 * level where the threshold is already crossed at the shoreline, which is what
	 * happens in Patagonia and the arctic, and a value above the world ceiling where
	 * it is never crossed, which is what happens in the deep tropics at sea level.
	 */
	public double altitudeOfThreshold(
			final double worldX, final double worldZ, final double celsius) {
		double atSeaLevel = zonalTemperature(worldZ)
				+ noise.sample(worldX, worldZ) * settings.seasonalNoiseScale();

		return (atSeaLevel - celsius) / settings.lapseRatePerBlock();
	}
}
