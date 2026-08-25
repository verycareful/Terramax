package com.fury.terramax.core.climate;

/**
 * Tuning for the moisture model.
 *
 * <p>Separate from {@link ClimateSettings} because it is a separate decision. Latitude
 * bands and a lapse rate are settled physics with two or three numbers; a moisture
 * budget along a trajectory has a dozen, and burying them in the temperature record
 * would make both harder to read.
 *
 * @param latticeSpacingBlocks   spacing of the grid moisture is solved on
 * @param trajectorySteps        how many steps a back-trajectory takes
 * @param firstStepBlocks        the step nearest the query, as blocks of travel at
 *                               full wind speed
 * @param stepGrowth             factor each step grows by as it recedes upwind
 * @param referenceTemperature   temperature at which saturation is exactly 1, in C
 * @param saturationDoubling     degrees C over which saturation doubles
 * @param evaporationLengthBlocks time, in the same units as the steps, over which
 *                               ocean air closes 1 - 1/e of its gap to saturation
 * @param landEvaporationFraction land evaporation as a fraction of the ocean rate
 * @param condensationEfficiency  fraction of the excess above saturation that falls
 *                               out per step, rather than staying aloft as cloud
 * @param latentHeatCelsius      warming per unit of vapour condensed, in C
 * @param thermalRelaxationBlocks distance over which the parcel forgets its history
 *                               and returns to the ambient temperature
 * @param convergenceRainFactor  rain produced where the circulation converges
 * @param subsidenceDryingFactor how fast sinking air is dried by the dry upper air it
 *                               is descending out of
 * @param rainWindowBlocks       decay length of the weighting that turns rain along
 *                               the trajectory into a rate reported at the query
 * @param cacheNodeLimit         solved nodes held before the cache is dropped
 */
public record MoistureSettings(
		double latticeSpacingBlocks,
		int trajectorySteps,
		double firstStepBlocks,
		double stepGrowth,
		double referenceTemperature,
		double saturationDoubling,
		double evaporationLengthBlocks,
		double landEvaporationFraction,
		double condensationEfficiency,
		double latentHeatCelsius,
		double thermalRelaxationBlocks,
		double convergenceRainFactor,
		double subsidenceDryingFactor,
		double rainWindowBlocks,
		int cacheNodeLimit) {

	/**
	 * Defaults chosen against this world's horizontal scale.
	 *
	 * <p><b>The step schedule is geometric, and that is the whole reason this is
	 * affordable.</b> Forty steps starting at 600 blocks and growing by 9 percent
	 * reach back about 200,000 blocks, two large plates, while still resolving the
	 * range you are standing behind in ten steps. A uniform schedule cannot do both:
	 * at 600 blocks uniform the trace covers 24,000 blocks and every continental
	 * interior is coastal, and at 5,000 blocks uniform a mountain range is one step
	 * wide and casts no shadow. Detail is wanted where the air is now and only
	 * provenance is wanted 200,000 blocks upwind, so the steps grow.
	 *
	 * <p><b>Saturation doubles every 10.5 C</b>, which is the Clausius-Clapeyron
	 * relation to the accuracy anything here needs. It is the single most important
	 * number in the model: it is why the tropics are wet and the arctic is dry
	 * despite both being saturated, and why air that has climbed a range cannot hold
	 * what it held at the foot.
	 *
	 * <p><b>Latent heat is 25 C per unit of vapour, and the moist lapse rate is not a
	 * setting.</b> Condensing warms the parcel, and that warming partly cancels the
	 * adiabatic cooling of the climb. The moist rate is therefore an outcome, near
	 * half the dry rate under mid-latitude conditions and closer to it in cold dry
	 * air, exactly as on Earth. Writing the ratio down as a constant instead would
	 * have made it wrong everywhere except where it was tuned.
	 *
	 * <p><b>Rain is a weighted mean over the tail of the trajectory, not the final
	 * step.</b> Whether the very last step happened to be climbing is a coin flip,
	 * and reporting it directly turned a continent into salt-and-pepper: half the
	 * nodes soaking, half bone dry, neighbours disagreeing completely. The weight
	 * decays over 4,000, roughly half a mountain flank, which is short enough that a
	 * lee stays in shadow and long enough that a dozen steps contribute.
	 *
	 * <p><b>The convergence factor is small because it competes with evaporation, not
	 * with orography.</b> At 60 it stripped vapour faster than any ocean could replace
	 * it and left the equator at two percent humidity, which is the opposite of the
	 * wettest belt on the planet. It is set so that ocean air under the doldrums
	 * settles near saturation rather than being wrung dry by the very convergence
	 * that is supposed to make it rain.
	 *
	 * <p><b>Subsidence drying is set so ocean stays humid and land does not.</b> At 4.0
	 * against the same 25,000 evaporation length, subtropical ocean air settles near
	 * eighty percent humidity because evaporation keeps pace with the drying, while
	 * land, which recharges at twelve percent of that rate, falls to around thirty.
	 * That gap is the whole difference between a subtropical sea and the desert on its
	 * shore, and neither had to be placed.
	 *
	 * <p>{@code latticeSpacingBlocks} of 512 is far finer than moisture varies, which
	 * is the point: it is fine enough that interpolating between nodes is invisible,
	 * and coarse enough that a chunk costs a fraction of one trace.
	 */
	public static MoistureSettings defaults() {
		return new MoistureSettings(
				512.0, 40, 600.0, 1.09,
				15.0, 10.5,
				25_000.0, 0.12,
				0.55, 25.0, 25_000.0,
				3.5,
				4.0,
				4_000.0,
				1 << 20);
	}

	/** The same model solved on a coarser grid, for views too wide to solve at 512. */
	public MoistureSettings withLatticeSpacing(final double blocks) {
		return new MoistureSettings(
				blocks, trajectorySteps, firstStepBlocks, stepGrowth,
				referenceTemperature, saturationDoubling,
				evaporationLengthBlocks, landEvaporationFraction,
				condensationEfficiency, latentHeatCelsius, thermalRelaxationBlocks,
				convergenceRainFactor, subsidenceDryingFactor,
				rainWindowBlocks, cacheNodeLimit);
	}

	/**
	 * How far a trajectory reaches back upwind, in blocks, at full wind speed.
	 *
	 * <p>An upper bound rather than a fixed reach. Steps are steps in time, so air in
	 * a calm belt is traced only a few thousand blocks and air in the trades most of
	 * this.
	 */
	public double fetchBlocks() {
		double total = 0.0;
		double step = firstStepBlocks;

		for (int i = 0; i < trajectorySteps; i++) {
			total += step;
			step *= stepGrowth;
		}

		return total;
	}
}
