package com.fury.terramax.core.climate;

/**
 * The state of the air arriving at one position.
 *
 * <p><b>Vapour and precipitation are separate on purpose.</b> Vapour is what the air
 * carries; precipitation is what falls out of it. A single moisture scalar cannot tell
 * them apart, and the difference is exactly the difference between the Amazon and a
 * warm coastal desert. Coastal deserts sit under air that is nearly saturated and
 * still never rains, because nothing lifts it: high vapour, zero precipitation. Fold
 * the two into one number and that place cannot exist.
 *
 * <p>{@code saturation} rides along because vapour on its own is not a climate signal.
 * Cold saturated air holds a fraction of what warm saturated air holds, so 0.3 units of
 * vapour is bone dry in the tropics and fog in the arctic. The ratio is what a biome
 * wants, and carrying the denominator means the ratio is available without re-deriving
 * temperature.
 *
 * <p>{@code foehnWarming} is the parcel's departure from the ambient temperature its
 * latitude and altitude would give. It is not a decoration. Air that rains itself out
 * climbing a range cools at the moist rate on the way up and warms at the dry rate on
 * the way down, so it reaches the far foot warmer than it left the near one. That is
 * the foehn, and it is why a lee slope is not merely a dry version of its windward
 * twin. Consumers add this to {@link TemperatureField#at} rather than the field
 * applying it itself, which is what keeps temperature free of any dependence on
 * moisture.
 *
 * @param vapour       water carried by the air, in units where saturation at the
 *                     reference temperature is 1
 * @param saturation   the most this air could carry at its own temperature
 * @param precipitation rain falling here, as a rate per unit of path travelled
 * @param foehnWarming parcel temperature minus ambient, in degrees C
 */
public record Moisture(
		double vapour,
		double saturation,
		double precipitation,
		double foehnWarming) {

	/** Vapour as a fraction of what this air could hold, roughly relative humidity. */
	public double humidity() {
		return saturation <= 0.0 ? 0.0 : Math.min(1.0, vapour / saturation);
	}

	/** Linear blend toward another sample, used to interpolate between lattice nodes. */
	public Moisture lerp(final Moisture other, final double t) {
		return new Moisture(
				vapour + (other.vapour - vapour) * t,
				saturation + (other.saturation - saturation) * t,
				precipitation + (other.precipitation - precipitation) * t,
				foehnWarming + (other.foehnWarming - foehnWarming) * t);
	}
}
