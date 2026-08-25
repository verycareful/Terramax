package com.fury.terramax.core.climate;

import java.util.concurrent.ConcurrentHashMap;

import com.fury.terramax.core.terrain.HeightField;

/**
 * Vapour, rain and foehn warming anywhere in the world.
 *
 * <p><b>Nothing here is placed.</b> There is no rain-shadow term, no desert belt, no
 * rainforest band. There is one air parcel, followed along the streamline that brought
 * it here, gaining vapour where it crosses water and losing it wherever it is cooled
 * past what it can hold. Every pattern the map shows falls out of that: the wet
 * windward face and the dry lee, the arid continental interior, the equatorial rain
 * belt, and the coastal desert that is humid and rainless at the same time.
 *
 * <p><b>The trajectory is traced against the deflected wind, not the base flow.</b>
 * A straight line upwind cannot produce a foehn, because a foehn requires the air to
 * have gone over something. It also cannot produce the case where moisture pours
 * through one gap in a range and soaks a single interior valley while its neighbour
 * stays dry, and that case is most of what makes a mountain map interesting. So each
 * step back re-evaluates the wind where it lands.
 *
 * <p><b>Why this is affordable.</b> A trace is forty wind evaluations and each of those
 * is four uplift evaluations, so a solved point costs about two hundred uplift lookups.
 * Per column that would be ruinous. But moisture varies over tens of thousands of
 * blocks and does not need per-column resolution, so it is solved on a lattice and
 * interpolated. At 512-block spacing a 16 by 16 chunk covers a thousandth of a cell,
 * which is a fraction of one trace amortised over the chunk.
 *
 * <p>The lattice is a legitimate structure rather than the kind of cache that was cut
 * from the plate design. Every node is a pure function of its own coordinate, solvable
 * in any order, on any thread, with no dependence on what has been generated before.
 * Nothing about a chunk changes according to whether its neighbour was generated first.
 *
 * <p><b>The parcel carries temperature as well as vapour, and that is what buys the
 * foehn.</b> Rising air cools; if it is condensing, the latent heat released partly
 * cancels that cooling, so it cools more slowly than it will later warm on the way
 * down. Air that rains itself out over a range therefore arrives at the far foot both
 * drier and warmer than it left the near one. The moist lapse rate is never written
 * down anywhere in this class: it is what happens.
 */
public final class MoistureField {
	/**
	 * Times condensation is re-solved within one step.
	 *
	 * <p>Condensing releases heat, the heat raises the saturation limit, and the
	 * higher limit means less should have condensed. One pass overshoots. Three
	 * settle it to well under a degree.
	 */
	private static final int CONDENSATION_PASSES = 3;

	/** Vapour a land-born parcel starts with, as a fraction of saturation. */
	private static final double LAND_INITIAL_HUMIDITY = 0.5;

	/** Window multiples past which a step's rain is dropped rather than weighted. */
	private static final double RAIN_WEIGHT_CUTOFF = 4.0;

	/** Separation for the divergence estimate, as a fraction of a climate band. */
	private static final double DIVERGENCE_STEP_FRACTION = 0.01;

	/**
	 * Interval precipitation is reported over, in blocks of travel at full wind speed.
	 *
	 * <p>Rain is a rate per unit time, not per unit ground. Reporting it per block
	 * covered would make every calm belt on the planet read as a monsoon, because the
	 * denominator goes to zero exactly where the air stops moving.
	 */
	private static final double PRECIPITATION_REFERENCE_TIME = 1_000.0;

	private final ClimateSettings climate;
	private final MoistureSettings settings;
	private final TemperatureField temperature;
	private final WindField wind;
	private final HeightField uplift;
	private final double seaLevel;

	/** Degrees C per e-fold of saturation, derived from the doubling interval. */
	private final double saturationScale;

	/**
	 * Weight each trailing step's rain carries, index 0 arriving at the query.
	 *
	 * <p>Decaying rather than flat, and the difference is the whole rain shadow. A
	 * flat average over a window wide enough to be stable is also wide enough to
	 * smear the wet windward face across the dry lee behind it, and the two cancel
	 * into uniform green. Decaying keeps the footprint at roughly half a mountain
	 * flank while still drawing on a dozen steps, so the shadow survives and the
	 * salt-and-pepper does not come back.
	 */
	private final double[] rainWeights;

	/** Sum of weight times elapsed, the denominator that turns the sum into a rate. */
	private final double rainNormaliser;

	/**
	 * Time steps, index 0 being the one that arrives at the query.
	 *
	 * <p>Measured in blocks of travel at full wind speed, so a step is a distance
	 * where the air is racing and barely a nudge where it is stalled.
	 */
	private final double[] stepLengths;

	private final ConcurrentHashMap<Long, Moisture> solved = new ConcurrentHashMap<>();

	/**
	 * @param uplift the large-scale terrain component, not the finished surface, for
	 *               the same reason {@link WindField} takes it: the finished surface
	 *               will depend on rivers, rivers on moisture, and the loop has to be
	 *               cut somewhere that is also physically right
	 */
	public MoistureField(
			final ClimateSettings climate, final MoistureSettings settings,
			final TemperatureField temperature, final WindField wind,
			final HeightField uplift, final double seaLevel) {
		this.climate = climate;
		this.settings = settings;
		this.temperature = temperature;
		this.wind = wind;
		this.uplift = uplift;
		this.seaLevel = seaLevel;
		this.saturationScale = settings.saturationDoubling() / Math.log(2.0);

		this.stepLengths = new double[settings.trajectorySteps()];

		double step = settings.firstStepBlocks();

		for (int i = 0; i < stepLengths.length; i++) {
			stepLengths[i] = step;
			step *= settings.stepGrowth();
		}

		this.rainWeights = new double[stepLengths.length];

		double window = settings.rainWindowBlocks();
		double before = 0.0;
		double normaliser = 0.0;

		for (int i = 0; i < stepLengths.length; i++) {
			double midpoint = before + stepLengths[i] * 0.5;

			before += stepLengths[i];

			// Cut off once the weight is negligible, so the loop below can stop
			// early rather than carrying rain that fell a continent away.
			if (midpoint > window * RAIN_WEIGHT_CUTOFF) {
				break;
			}

			rainWeights[i] = Math.exp(-midpoint / window);
			normaliser += rainWeights[i] * stepLengths[i];
		}

		this.rainNormaliser = normaliser;
	}

	public MoistureSettings settings() {
		return settings;
	}

	/**
	 * The most vapour air at this temperature can hold.
	 *
	 * <p>Exponential, doubling every {@link MoistureSettings#saturationDoubling}
	 * degrees. This one curve is why the tropics are wet and the arctic is dry even
	 * though both are saturated, and why a parcel that has climbed a kilometre must
	 * drop most of what it was carrying.
	 */
	public double saturation(final double celsius) {
		return Math.exp((celsius - settings.referenceTemperature()) / saturationScale);
	}

	/** Moisture at a position, interpolated from the four surrounding lattice nodes. */
	public Moisture at(final double worldX, final double worldZ) {
		double spacing = settings.latticeSpacingBlocks();
		double gridX = worldX / spacing;
		double gridZ = worldZ / spacing;

		long nodeX = (long) Math.floor(gridX);
		long nodeZ = (long) Math.floor(gridZ);

		// Smoothstep rather than raw linear. Bilinear interpolation of a field this
		// structured leaves visible facets: the value is continuous across a node
		// boundary but its slope is not, and the eye reads the kink as a tile edge.
		double fractionX = smoothstep(gridX - nodeX);
		double fractionZ = smoothstep(gridZ - nodeZ);

		Moisture near = node(nodeX, nodeZ).lerp(node(nodeX + 1, nodeZ), fractionX);
		Moisture far = node(nodeX, nodeZ + 1).lerp(node(nodeX + 1, nodeZ + 1), fractionX);

		return near.lerp(far, fractionZ);
	}

	/**
	 * A solved lattice node, from the cache or freshly traced.
	 *
	 * <p>Deliberately {@code get} then {@code put} rather than
	 * {@code computeIfAbsent}: the latter holds a bin lock for the whole trace, which
	 * on twelve threads rendering a map serialises unrelated nodes that happen to
	 * hash together. Two threads racing on the same node duplicate the work and
	 * write the same answer, because a trace is a pure function of its coordinate.
	 */
	private Moisture node(final long nodeX, final long nodeZ) {
		// Node indices fit 32 bits out to half a trillion blocks either way, which is
		// four orders of magnitude past any world that will be generated.
		long key = (nodeX & 0xFFFFFFFFL) << 32 | (nodeZ & 0xFFFFFFFFL);

		Moisture cached = solved.get(key);

		if (cached != null) {
			return cached;
		}

		// Bounded rather than evicted. Recomputing a dropped node gives the identical
		// answer, so throwing the whole map away costs time and never correctness.
		if (solved.size() >= settings.cacheNodeLimit()) {
			solved.clear();
		}

		double spacing = settings.latticeSpacingBlocks();
		Moisture fresh = solve(nodeX * spacing, nodeZ * spacing);

		solved.put(key, fresh);

		return fresh;
	}

	/**
	 * Follows the air that arrives here back to where it came from, then forward.
	 *
	 * <p>Two passes because the budget only integrates one way. Where the parcel is
	 * now is known and where it came from is not, so the path is found by stepping
	 * backwards along the wind; the vapour it carries depends on everything it has
	 * crossed, so the budget is then integrated forwards along that same path.
	 *
	 * <p>Public because the simulator's probe wants one exact answer at a clicked
	 * point rather than the interpolated lattice value.
	 */
	public Moisture solve(final double worldX, final double worldZ) {
		int steps = settings.trajectorySteps();

		double[] pathX = new double[steps + 1];
		double[] pathZ = new double[steps + 1];
		double[] pathSpeed = new double[steps + 1];

		pathX[steps] = worldX;
		pathZ[steps] = worldZ;

		for (int k = steps; k > 0; k--) {
			Wind flow = wind.at(pathX[k], pathZ[k], temperature.latitude(pathZ[k]));

			pathSpeed[k] = flow.speed();

			// The step is a step in TIME, and the ground it covers is speed times
			// that. Stepping a fixed distance instead tears the map along every line
			// where the circulation reverses: the wind there is near zero but points
			// opposite ways a hair either side, so a fixed-distance trace swings two
			// hundred thousand blocks across the discontinuity while the wind driving
			// it is imperceptible. Scaling by speed collapses the trajectory to a
			// point exactly where the wind vanishes, which is both continuous and
			// what calm air actually does: it sits there.
			double elapsed = stepLengths[steps - k];

			pathX[k - 1] = pathX[k] - flow.eastward() * elapsed;
			pathZ[k - 1] = pathZ[k] - flow.southward() * elapsed;
		}

		pathSpeed[0] = wind.at(pathX[0], pathZ[0], temperature.latitude(pathZ[0])).speed();

		return integrate(pathX, pathZ, pathSpeed);
	}

	/** Carries the vapour and heat budget forward along a traced path. */
	private Moisture integrate(
			final double[] pathX, final double[] pathZ, final double[] pathSpeed) {
		int steps = stepLengths.length;

		double previousAltitude = altitudeAt(pathX[0], pathZ[0]);
		double parcelTemperature = temperature.at(pathX[0], pathZ[0], previousAltitude);

		// A parcel born over water starts saturated; one born over land starts half
		// way. Two hundred thousand blocks downwind this hardly survives, which is the
		// point of tracing that far.
		double vapour = saturation(parcelTemperature)
				* (previousAltitude <= seaLevel ? 1.0 : LAND_INITIAL_HUMIDITY);

		double recentRain = 0.0;

		for (int k = 1; k <= steps; k++) {
			double elapsed = stepLengths[steps - k];
			double surface = uplift.heightAt(pathX[k], pathZ[k]);
			boolean overWater = surface < seaLevel;
			double altitude = Math.max(seaLevel, surface);

			double rise = altitude - previousAltitude;

			// Dry adiabatic, in both directions. Descending air warms by exactly what
			// ascending air loses, and the asymmetry that makes a foehn comes entirely
			// from the latent heat added back below.
			parcelTemperature -= rise * climate.lapseRatePerBlock();

			// The parcel gradually forgets its history: ground and sun pull it back
			// toward the temperature its latitude and altitude imply. Without this a
			// foehn would persist across a continent instead of fading over tens of
			// thousands of blocks.
			double ambient = temperature.at(pathX[k], pathZ[k], altitude);
			double relaxation = 1.0 - Math.exp(-elapsed / settings.thermalRelaxationBlocks());

			parcelTemperature += (ambient - parcelTemperature) * relaxation;

			vapour = gainVapour(vapour, parcelTemperature, elapsed, overWater);

			double fell = 0.0;

			// Latent heat can at most cancel the cooling of the climb, never reverse
			// it. That bound is what a moist adiabat is, and enforcing it here means
			// the moist lapse rate never has to be written down as a number.
			double heatBudget = Math.max(0.0, rise * climate.lapseRatePerBlock());

			for (int pass = 0; pass < CONDENSATION_PASSES; pass++) {
				double capacity = saturation(parcelTemperature);

				if (vapour <= capacity) {
					break;
				}

				double condensed = (vapour - capacity) * settings.condensationEfficiency();

				vapour -= condensed;
				fell += condensed;

				double warming = Math.min(condensed * settings.latentHeatCelsius(), heatBudget);

				parcelTemperature += warming;
				heatBudget -= warming;
			}

			double divergence = -baseConvergence(pathZ[k]);

			if (divergence > 0.0) {
				// Air is sinking here, and sinking air is air arriving from aloft,
				// where it rained out long ago. It does not merely fail to rain: it
				// actively mixes dryness downward. This is the Sahara and the
				// Australian interior, and it is why the subtropical ocean beside them
				// stays humid while the land does not. Evaporation over water keeps
				// pace with this; twelve percent of it over land does not.
				vapour *= Math.exp(-settings.subsidenceDryingFactor() * divergence * elapsed);
			}

			double converged = convergenceRain(vapour, pathZ[k], elapsed);

			vapour -= converged;
			fell += converged;

			// Rain from the tail of the trajectory, weighted toward the near end.
			// What falls in one step is a coin flip on whether that step happened to
			// be climbing; what falls over the last stretch of country is a climate.
			// Earlier rain is excluded because it fell somewhere else.
			recentRain += fell * rainWeights[steps - k];

			previousAltitude = altitude;
		}

		double ambientHere = temperature.at(pathX[steps], pathZ[steps], previousAltitude);

		return new Moisture(
				vapour,
				saturation(parcelTemperature),
				recentRain / rainNormaliser * PRECIPITATION_REFERENCE_TIME,
				parcelTemperature - ambientHere);
	}

	/**
	 * Vapour picked up over the step, relaxing toward saturation.
	 *
	 * <p>Driven by elapsed time rather than ground covered, which is why the doldrums
	 * end up saturated. Air that is going nowhere is still sitting over water, and it
	 * takes up as much per hour as air racing across the same ocean.
	 */
	private double gainVapour(
			final double vapour, final double parcelTemperature,
			final double elapsed, final boolean overWater) {
		double capacity = saturation(parcelTemperature);

		if (vapour >= capacity) {
			return vapour;
		}

		double rate = overWater ? 1.0 : settings.landEvaporationFraction();

		double taken = 1.0 - Math.exp(
				-elapsed * rate / settings.evaporationLengthBlocks());

		return vapour + (capacity - vapour) * taken;
	}

	/**
	 * Rain from the circulation converging, with no terrain involved.
	 *
	 * <p>This is the term that puts a rain belt on the equator and leaves the horse
	 * latitudes dry, and it needs no new machinery: the base flow's eastward
	 * component depends only on latitude, so the field's divergence is one derivative
	 * of its meridional component in Z. Air piling up has to go somewhere, and the
	 * only direction left is up.
	 *
	 * <p>Per unit time, not per unit ground covered. That distinction is what puts
	 * rain on the equator at all: the doldrums converge hardest and move least, so a
	 * per-distance term would leave the wettest belt on the planet bone dry.
	 */
	private double convergenceRain(
			final double vapour, final double worldZ, final double elapsed) {
		double convergence = baseConvergence(worldZ);

		if (convergence <= 0.0) {
			return 0.0;
		}

		double fraction = Math.min(
				0.5, settings.convergenceRainFactor() * convergence * elapsed);

		return vapour * fraction;
	}

	/**
	 * How hard the circulation is piling air up here, per block, positive inward.
	 *
	 * <p>One derivative serves both halves of the cell. Where it is positive air rises
	 * and rains; where it is negative air sinks and dries. Those are the equatorial
	 * rain belt and the subtropical desert belt, and they are the same number.
	 */
	public double baseConvergence(final double worldZ) {
		double separation = climate.bandHeightBlocks() * DIVERGENCE_STEP_FRACTION;

		double south = wind.baseFlow(
				worldZ + separation, temperature.latitude(worldZ + separation)).southward();
		double north = wind.baseFlow(
				worldZ - separation, temperature.latitude(worldZ - separation)).southward();

		return -(south - north) / (2.0 * separation);
	}

	/** Hermite ease, flat at both ends, so interpolated tiles do not show their seams. */
	private static double smoothstep(final double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	/** Height of the surface the air rides over, never below sea level. */
	private double altitudeAt(final double worldX, final double worldZ) {
		return Math.max(seaLevel, uplift.heightAt(worldX, worldZ));
	}
}
