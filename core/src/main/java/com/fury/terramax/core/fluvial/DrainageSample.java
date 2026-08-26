package com.fury.terramax.core.fluvial;

/**
 * What a column learns by asking the drainage network about itself.
 *
 * <p>Everything the carve needs and nothing it does not. The two distances the search
 * actually found are collapsed into {@link #hillslope()} here rather than passed on,
 * because no consumer has any use for them separately.
 *
 * @param channelElevation water surface at the nearest channel, in blocks
 * @param distance         blocks to that channel
 * @param discharge        accumulated runoff, relative, driving floodplain width
 * @param order            Strahler order of the nearest channel
 * @param hillslope        0 on a channel, 1 on the divide
 * @param lakeSurface      standing water level here, or {@link #NO_LAKE}
 * @param endorheic        whether this basin fails to reach the sea
 */
public record DrainageSample(
		double channelElevation,
		double distance,
		double discharge,
		int order,
		double hillslope,
		double lakeSurface,
		boolean endorheic) {

	/** No standing water here. Chosen so a plain comparison against ground is false. */
	public static final double NO_LAKE = Double.NEGATIVE_INFINITY;

	/**
	 * A point with no channel anywhere near it.
	 *
	 * <p>Reads as a divide, which is the safe answer: the carve then leaves the full
	 * uplift budget standing rather than cutting a valley toward a channel that was
	 * never found.
	 */
	public static DrainageSample none(final double budget) {
		return new DrainageSample(budget, Double.MAX_VALUE, 0.0, 0, 1.0, NO_LAKE, false);
	}

	public boolean hasChannel() {
		return distance < Double.MAX_VALUE;
	}

	public boolean underLake(final double groundHeight) {
		return lakeSurface > groundHeight;
	}
}
