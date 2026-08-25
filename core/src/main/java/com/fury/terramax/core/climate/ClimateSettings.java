package com.fury.terramax.core.climate;

/**
 * Tuning for the climate system.
 *
 * @param bandHeightBlocks   distance from equator to pole, in blocks
 * @param equatorTemperature sea-level temperature at the equator, in degrees C
 * @param poleTemperature    sea-level temperature at the pole, in degrees C
 * @param lapseRatePerBlock  temperature lost per block of altitude, in degrees C
 * @param seasonalNoiseScale strength of the local departure from the zonal average
 * @param noiseWavelength    wavelength of that departure, in blocks
 * @param foehnFraction      how much of the modelled air-temperature anomaly reaches
 *                           the ground, in [0, 1]
 */
public record ClimateSettings(
		double bandHeightBlocks,
		double equatorTemperature,
		double poleTemperature,
		double lapseRatePerBlock,
		double seasonalNoiseScale,
		double noiseWavelength,
		double foehnFraction) {

	/**
	 * Defaults for a world whose vertical scale is compressed relative to Earth.
	 *
	 * <p><b>The lapse rate is deliberately exaggerated.</b> Earth loses about 6.5°C
	 * per 1,000 m. The tallest ranges here reach 1,600 blocks, so at one degree per
	 * Earth-metre a whole mountain would cool by 10°C, which is not enough to climb
	 * out of forest into rock and snow unless it was already near freezing at the
	 * foot. At 0.022 per block a 1,600-block peak is 35°C colder than its base, so a
	 * tropical mountain genuinely has snow on it. This is the same compression the
	 * horizontal scale already has, applied vertically.
	 *
	 * <p>{@code bandHeightBlocks} of 1,200,000 puts pole and equator about fifteen
	 * plate widths apart. Large enough that a continent sits in one or two climate
	 * zones rather than spanning all of them, small enough that the whole range is
	 * reachable.
	 *
	 * <p>30°C at the equator and -25°C at the pole are Earth's rough annual means at
	 * sea level, and there is no reason to invent different ones.
	 *
	 * <p><b>Half the foehn anomaly reaches the ground.</b> The trajectory model
	 * produces departures past 10°C on a steep lee, which is the upper end of what a
	 * real foehn does and rests on tuned rather than measured quantities. Half keeps
	 * lee slopes visibly warmer, and their treelines visibly higher, without letting
	 * one modelled term take over the life-zone map. See {@link SurfaceClimate}.
	 */
	public static ClimateSettings defaults() {
		return new ClimateSettings(1_200_000.0, 30.0, -25.0, 0.022, 6.0, 220_000.0, 0.5);
	}
}
