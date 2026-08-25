package com.fury.terramax.core.climate;

/**
 * What the ground actually experiences: temperature after the air's history is counted.
 *
 * <p><b>Why this is a separate class.</b> {@link TemperatureField} is latitude and
 * altitude and nothing else, and it is worth keeping that way: it is two terms, it is
 * cheap, and it depends on nothing. But the air arriving at a lee slope has been warmed
 * by descending out of a range it rained itself out over, and that warming is real
 * ground truth, not a decoration. Folding it into the temperature field itself would
 * make temperature depend on wind, on terrain, and on a forty-step trajectory, and
 * every caller that only wanted the zonal average would pay for it.
 *
 * <p>So the coupling lives here. Anything that wants the honest surface temperature
 * asks this; anything that wants the underlying banding asks the field directly.
 *
 * <p><b>Only a fraction of the anomaly is applied.</b> The trajectory model produces
 * departures of ten degrees and more on a steep lee, which is at the upper end of what
 * a real foehn does and rests on a chain of assumptions, the condensation efficiency
 * and the thermal relaxation length among them, that are tuned rather than measured.
 * Damping keeps lee slopes visibly warmer and their treelines visibly higher without
 * letting one modelled term dominate the life-zone map.
 *
 * <p><b>Treeline and snowline stay contours.</b> They are still the altitude where the
 * temperature crosses a fixed threshold, exactly as before. The only change is that the
 * threshold is now crossed higher on a sheltered slope than on an exposed one, which is
 * what happens on real mountains and which no per-range setting had to say.
 */
public final class SurfaceClimate {
	private final ClimateSettings settings;
	private final TemperatureField temperature;
	private final MoistureField moisture;

	public SurfaceClimate(
			final ClimateSettings settings, final TemperatureField temperature,
			final MoistureField moisture) {
		this.settings = settings;
		this.temperature = temperature;
		this.moisture = moisture;
	}

	public TemperatureField temperature() {
		return temperature;
	}

	public MoistureField moisture() {
		return moisture;
	}

	/** Latitude at a world Z, 0 at the equator rising to 1 at the pole. */
	public double latitude(final double worldZ) {
		return temperature.latitude(worldZ);
	}

	/** How much the arriving air departs from the zonal expectation, in degrees C. */
	public double airAnomaly(final double worldX, final double worldZ) {
		return moisture.at(worldX, worldZ).foehnWarming() * settings.foehnFraction();
	}

	/** Temperature at a position and altitude, with the air's history counted. */
	public double at(final double worldX, final double worldZ, final double height) {
		return temperature.at(worldX, worldZ, height) + airAnomaly(worldX, worldZ);
	}

	/**
	 * Altitude at which this column crosses a temperature threshold, in blocks.
	 *
	 * <p>The anomaly shifts the contour rather than being applied after it: warmer air
	 * pushes the crossing higher by exactly the anomaly divided by the lapse rate,
	 * which is why a lee slope carries trees further up than the windward face of the
	 * same mountain.
	 */
	public double altitudeOfThreshold(
			final double worldX, final double worldZ, final double celsius) {
		return temperature.altitudeOfThreshold(worldX, worldZ, celsius)
				+ airAnomaly(worldX, worldZ) / settings.lapseRatePerBlock();
	}
}
