package com.fury.terramax.core.climate;

/**
 * Air movement at one position: a direction and a speed.
 *
 * <p>Components are in world axes, so {@code eastward} is +X and {@code southward}
 * is +Z. Magnitude is normalised so the fastest zonal flow is 1.
 *
 * @param eastward  x component of flow
 * @param southward z component of flow
 */
public record Wind(double eastward, double southward) {
	public double speed() {
		return Math.hypot(eastward, southward);
	}

	/** Direction in radians, or 0 for dead calm where direction is meaningless. */
	public double bearing() {
		return speed() == 0.0 ? 0.0 : Math.atan2(southward, eastward);
	}

	/**
	 * How strongly this wind blows from a given direction toward the query.
	 *
	 * <p>Positive where the wind arrives from that heading. Used to work out whether
	 * a mountain flank faces into the weather or shelters behind it.
	 */
	public double alignment(final double dirX, final double dirZ) {
		double length = Math.hypot(dirX, dirZ);

		return length == 0.0 ? 0.0 : (eastward * dirX + southward * dirZ) / length;
	}
}
