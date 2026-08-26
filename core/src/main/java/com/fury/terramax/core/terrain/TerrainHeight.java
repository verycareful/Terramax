package com.fury.terramax.core.terrain;

import com.fury.terramax.core.fluvial.DrainageMap;
import com.fury.terramax.core.fluvial.DrainageSample;
import com.fury.terramax.core.fluvial.DrainageSettings;
import com.fury.terramax.core.util.FractalNoise2D;
import java.util.concurrent.atomic.LongAdder;

/**
 * The surface elevation of a Terramax world, carved from its drainage.
 *
 * <p><b>This is no longer an additive stack, and that is the whole point of the
 * change.</b> It used to be crust base plus boundary relief plus region relief plus
 * noise, with a separate fBm field cutting valleys into the result. Terrain and valleys
 * were two independent things that happened to be added together, so nothing stopped
 * them disagreeing: a valley could run along a ridge, or a river could climb.
 *
 * <p>Now uplift is a <b>budget</b> rather than a height. {@link UpliftHeight} says where
 * the ground would stand if nothing had ever eroded it, and the drainage network decides
 * how much of that survives at each point. At a channel almost none does, because the
 * surface is the channel. At a divide all of it does. Between them the hillslope profile
 * interpolates. Terrain and rivers cannot disagree because they are no longer two
 * things.
 *
 * <p>Pure function of position, as {@link HeightField} requires. No state, no ordering
 * dependency, so a chunk generated alone matches the same chunk generated in a batch.
 */
public final class TerrainHeight implements HeightField {
	private static final long SALT_DETAIL = 0xD1B54A32D192ED03L;

	private static final int DETAIL_OCTAVES = 4;

	/**
	 * Height above sea level at which detail reaches full strength.
	 *
	 * <p>Fading detail in over the first stretch of elevation keeps coastlines from
	 * fragmenting into noise, which happens when full-amplitude roughness is applied to
	 * ground sitting a few blocks above the waterline.
	 */
	private static final double DETAIL_RAMP_BLOCKS = 120.0;

	/**
	 * Discharge at which a floodplain reaches its full modelled width.
	 *
	 * <p>Measured against this world rather than assumed: the largest trunk found
	 * carries about 0.6 in the units accumulation produces, so a reference of 1.0 puts
	 * the biggest rivers near the top of the curve and leaves creeks, three orders of
	 * magnitude below, with essentially none. That spread is the point. One expression
	 * then produces a broad flat floodplain on a trunk and a V-notch on a creek, with
	 * discharge as the only thing that differs between them.
	 */
	private static final double FLOODPLAIN_REFERENCE_DISCHARGE = 1.0;

	private final UpliftHeight uplift;
	private final DrainageMap drainage;
	private final TerrainSettings settings;

	/**
	 * Carve tuning, which lives with drainage rather than with terrain.
	 *
	 * <p>Floodplain width, the hillslope profile and the detail floor all describe how
	 * a river shapes the ground around it, so they belong with the river. Terrain no
	 * longer has an opinion about valleys of its own.
	 */
	private final DrainageSettings carve;
	private final FractalNoise2D detail;
	private final double seaLevel;
	private final boolean withCreeks;
	private final LongAdder inversionClamps = new LongAdder();
	private final LongAdder inversionSamples = new LongAdder();
	private final java.util.concurrent.atomic.DoubleAdder inversionExcess =
			new java.util.concurrent.atomic.DoubleAdder();
	private final java.util.concurrent.atomic.AtomicLong worstExcessBits =
			new java.util.concurrent.atomic.AtomicLong();

	public TerrainHeight(
			final long seed,
			final UpliftHeight uplift,
			final DrainageMap drainage,
			final TerrainSettings settings) {
		this(seed, uplift, drainage, settings, true);
	}

	/**
	 * A view of the same surface with tier 3 creeks left out.
	 *
	 * <p>For wide renders only. Creeks are a few blocks across and a continental pixel
	 * is 820, so at that scale they cannot be drawn and building them costs minutes.
	 * The game always uses the full surface.
	 */
	public TerrainHeight(
			final long seed,
			final UpliftHeight uplift,
			final DrainageMap drainage,
			final TerrainSettings settings,
			final boolean withCreeks) {
		this.withCreeks = withCreeks;
		this.uplift = uplift;
		this.drainage = drainage;
		this.settings = settings;
		this.carve = drainage.settings();
		this.detail = FractalNoise2D.standard(
				seed ^ SALT_DETAIL, DETAIL_OCTAVES, settings.detailWavelength());
		this.seaLevel = uplift.seaLevel();
	}

	public UpliftHeight uplift() {
		return uplift;
	}

	public DrainageMap drainage() {
		return drainage;
	}

	public TectonicHeight tectonic() {
		return uplift.tectonic();
	}

	/**
	 * How often the nearest channel stood above the column asking about it.
	 *
	 * <p><b>Not a defect, and the first name for it was wrong.</b> Channels are found by
	 * horizontal distance, so on steep ground the nearest one can belong to a different
	 * level of the landscape entirely: a channel on a shoulder is nearer to a point in
	 * the gorge below it than that gorge's own channel is, and the uplift surface can
	 * drop hundreds of blocks inside a single 1,000-block lattice cell.
	 *
	 * <p>When that happens the column is not in that channel's valley, so the right
	 * answer is to leave the ground standing at its uplift budget, uncarved. That is
	 * exactly what the clamp does, and it is the same result the divide case produces.
	 *
	 * <p>Worth measuring anyway, and by size rather than only by count. Twelve percent of
	 * land columns at a mean of 37 blocks is steep terrain behaving as steep terrain. The
	 * same count at a mean of several hundred would mean channel elevations were being
	 * assigned wrongly, which is how the water surface being used as the bed was caught:
	 * that showed as a fifth of all columns.
	 *
	 * <p>Removing it entirely would need each column routed to the channel it actually
	 * drains to, which is the unbounded upstream walk the whole tiered design exists to
	 * avoid.
	 */
	public long inversionClamps() {
		return inversionClamps.sum();
	}

	public long inversionSamples() {
		return inversionSamples.sum();
	}

	/** Mean blocks by which a clamped channel stood above its own budget. */
	public double meanInversionExcess() {
		long count = inversionClamps.sum();

		return count == 0 ? 0.0 : inversionExcess.sum() / count;
	}

	public double worstInversionExcess() {
		return Double.longBitsToDouble(worstExcessBits.get());
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		double budget = uplift.heightAt(worldX, worldZ);
		DrainageSample drain = drainage.sample(worldX, worldZ, withCreeks);

		double floor = drain.channelElevation();

		inversionSamples.increment();

		if (floor > budget) {
			// Recorded by size, not just by count. A clamp of a couple of blocks is the
			// lattice disagreeing with itself between sample points; a clamp of a
			// hundred is a model that is wrong. Counting alone cannot tell those apart,
			// and this guard exists to find the second kind.
			double excess = floor - budget;
			inversionClamps.increment();
			inversionExcess.add(excess);
			worstExcessBits.accumulateAndGet(Double.doubleToRawLongBits(excess),
					(a, b) -> Double.longBitsToDouble(a) >= Double.longBitsToDouble(b) ? a : b);
			floor = budget;
		}

		double height = floor
				+ (budget - floor) * hillslope(drain.hillslope(), drain.discharge());

		return height + detail.sample(worldX, worldZ)
				* detailAmplitude(drain.hillslope(), budget);
	}

	/**
	 * Standing water level here, or {@link DrainageSample#NO_LAKE}.
	 *
	 * <p>Reported rather than folded into the height, because the ground under a lake is
	 * the lake bed and raising it to the water level would turn every lake into a
	 * plateau. The shoreline is then wherever the finished surface crosses this level,
	 * which follows the terrain contour instead of the drainage lattice.
	 */
	public double waterLevelAt(final double worldX, final double worldZ) {
		return drainage.sample(worldX, worldZ, withCreeks).lakeSurface();
	}

	/**
	 * The profile from channel to divide.
	 *
	 * <p>Smoothstep rather than a power curve, because it has zero derivative at both
	 * ends: flat where it leaves the floodplain, and rounded where it reaches the
	 * divide. That is the real hillslope shape, concave footslope through straight
	 * midslope to convex crest. A bare power curve puts a kink along every divide in the
	 * world, and divides are continuous features that a player walks along.
	 *
	 * <p>The floodplain is a flat zone at the bottom whose width scales with discharge.
	 * It is one term and it does two jobs, because the only difference between a
	 * trunk's broad flat valley floor and a creek's V-notch is how much water passes
	 * through.
	 */
	private double hillslope(final double position, final double discharge) {
		double flood = Math.min(0.9, carve.floodplainWidthFactor()
				* Math.log1p(Math.max(0.0, discharge))
				/ Math.log1p(FLOODPLAIN_REFERENCE_DISCHARGE));

		if (position <= flood) {
			return 0.0;
		}

		double up = (position - flood) / (1.0 - flood);

		return Math.pow(TectonicHeight.smoothstep(up), carve.hillslopeExponent());
	}

	/**
	 * Detail amplitude, scaled by uplift <i>and</i> by hillslope position.
	 *
	 * <p>The second term is what the design asks for and what the old code could not do:
	 * valley floors come out flat and roughness increases up the slope. It also closes
	 * "mountain flanks are smooth", which sat on the open list from before moisture,
	 * because amplitude stops being a constant that every part of the landscape shares
	 * regardless of what it is.
	 */
	private double detailAmplitude(final double position, final double budget) {
		double floor = carve.detailFloorFraction();
		double slope = floor + (1.0 - floor) * position;
		double relief = Math.min(1.0, Math.max(0.0, (budget - seaLevel) / DETAIL_RAMP_BLOCKS));

		return settings.detailAmplitude() * slope * relief;
	}
}
