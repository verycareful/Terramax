package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.util.FractalNoise2D;
import com.fury.terramax.core.util.Hashing;

/**
 * Relief created at plate margins.
 *
 * <p>Returns a height offset in blocks, added to the blended crust base. The sign
 * matters: convergent margins push terrain up, divergent margins pull it down on
 * land and up on the ocean floor, and trenches go sharply negative.
 *
 * <p><b>Each margin has a type, and each type is one continuous profile read at a
 * signed distance.</b> That is what lets a range be asymmetric. Where oceanic crust
 * dives beneath continental the seaward flank is a trench and the landward one is a
 * wall, and the two are halves of a single expression rather than two expressions
 * that happen to meet.
 *
 * <p>They used to be two, and the cost of that was measured: each side chose its own
 * peak from the crust it stood on, so a subduction margin stepped by the difference
 * between an arc and a trench, and crossing any cell seam swapped the whole set of
 * margins in reach at once. Cross sections showed four vertical walls in one 12,000
 * block line, the largest a 1,340-block drop between two adjacent columns. Relief is
 * now built from {@link RangeType} and {@link PlateSample#across()}, both properties
 * of the pair of crust cells rather than of the column asking.
 */
public final class MountainRidge {
	/** Separates the two ridge fields from each other and from everything else. */
	private static final long SALT_RELIEF = 0x9E3779B185EBCA87L;
	private static final long SALT_GRAIN = 0x3C79AC492BA7B653L;

	/** Octaves in the along-range variation. Few, because it should undulate, not fizz. */
	private static final int RELIEF_OCTAVES = 3;

	/** Along-range variation wavelength, in crust spacings. */
	private static final double RELIEF_WAVELENGTH_FACTOR = 0.55;

	/**
	 * How sharply ridges are pinched.
	 *
	 * <p>{@code 1 - |noise|} already peaks along the zero crossings of the field,
	 * which is what makes a connected line of ridges rather than isolated bumps.
	 * Raising it above 1 narrows the crests and widens the valleys between them, which
	 * is the difference between rolling swells and mountains.
	 */
	private static final double GRAIN_SHARPNESS = 1.6;

	/**
	 * Where the trench sits on a subduction margin, as a fraction of the range
	 * half-width, negative being seaward.
	 *
	 * <p>Close to the contact rather than far out to sea, because the trench axis
	 * <i>is</i> the place the slab bends down, which is the plate contact itself.
	 */
	private static final double TRENCH_CENTRE = -0.15;

	/** How far the trench's influence reaches, as a fraction of the half-width. */
	private static final double TRENCH_WIDTH = 0.45;

	/**
	 * Where the arc crest stands, as a fraction of the half-width, inland of the
	 * contact.
	 *
	 * <p>Well inland, which is what makes the margin one-sided: a short violent rise
	 * from the coast to the crest, then a long descent the other way. The Andes do
	 * this over about 150km up and 800km down.
	 */
	private static final double ARC_CENTRE = 0.45;

	/** How far the arc's influence reaches. Wider than the trench, so the inland flank trails. */
	private static final double ARC_WIDTH = 0.55;

	/** How wide the dropped floor of a rift is, as a fraction of the half-width. */
	private static final double RIFT_FLOOR_WIDTH = 0.35;

	/** Where a rift's uplifted shoulders stand, either side of the floor. */
	private static final double RIFT_SHOULDER_CENTRE = 0.5;

	/** How wide those shoulders are. */
	private static final double RIFT_SHOULDER_WIDTH = 0.45;

	/** Decorrelates a suture's age from every other draw made against its identity. */
	private static final long SALT_SUTURE_AGE = 0x2545F4914F6CDD1DL;

	/** Keeps the suture belt field independent of the relief and grain fields. */
	private static final long SALT_SUTURE_BELT = 0x8A5CD789635D2DFFL;

	/**
	 * Wavelength of the field deciding where sutures run, in crust spacings.
	 *
	 * <p>Long, because a suture is a continental-scale feature. At 6,000-block cells
	 * this is 72,000 blocks, a little under two plate widths, so a belt crosses many
	 * cells and reads as one structure rather than as a patch.
	 */
	private static final double SUTURE_BELT_WAVELENGTH_FACTOR = 12.0;

	/**
	 * Field value above which a seam is part of a suture belt.
	 *
	 * <p>High, so belts are the exception. Most plate interior is quiet ground, which
	 * is what makes the belts worth finding.
	 */
	private static final double SUTURE_BELT_THRESHOLD = 0.55;

	/** Octaves in the belt field. Two, because a suture belt is a broad shape. */
	private static final int SUTURE_BELT_OCTAVES = 2;

	/**
	 * How quickly a belt reaches full strength past its threshold.
	 *
	 * <p>Narrow, so the belt reads as a gate rather than as a second amplitude. It
	 * still has to be a gradient rather than a step, or the edge of every belt would
	 * be a cliff.
	 */
	private static final double SUTURE_BELT_SOFTNESS = 0.06;

	/**
	 * How sharply a suture's relief falls away with age.
	 *
	 * <p>Above one, so most seams are old and nearly gone and a few are young and
	 * carry real relief. A linear draw would make the average seam a middling range
	 * and cover plate interiors in uniform corrugation, which is the diffuse
	 * interior noise this replaces, wearing a better hat.
	 */
	private static final double SUTURE_AGE_FALLOFF = 1.8;

	/**
	 * How much deeper a suture's valleys cut than any other range's, as a multiple of
	 * the shared grain depth.
	 *
	 * <p>The strongest grain of any type, because that is the one thing a worn
	 * collision has more of than a young one. Folded beds erode at different rates, so
	 * what survives is parallel ridge and valley: the Appalachians, the Urals. A young
	 * range has not had time to differentiate.
	 */
	private static final double SUTURE_GRAIN_DEPTH = 1.5;

	/**
	 * How wide a suture is, as a fraction of the ordinary range half-width.
	 *
	 * <p>Narrow, because an old range is eroded inward as well as downward. This is
	 * not a cosmetic choice: at the full half-width a suture is 3,180 blocks across
	 * while seams are 6,000 apart, so neighbouring sutures tile the continent and the
	 * result reads as ground that got higher rather than as a range that is there.
	 * Measured, loosening the belt gate to compensate pushed land from 34.5 percent of
	 * the map to 44.6 while the tallest suture still only reached y=188.
	 */
	private static final double SUTURE_WIDTH_FACTOR = 0.45;

	private final TerrainSettings settings;
	private final double rangeWidthBlocks;
	private final double blendWidthBlocks;
	private final FractalNoise2D reliefVariation;
	private final FractalNoise2D grain;
	private final FractalNoise2D sutureBelt;

	public MountainRidge(final long seed, final TerrainSettings settings, final double crustSpacing) {
		this.settings = settings;
		this.rangeWidthBlocks = settings.rangeWidthBlocks(crustSpacing);
		this.blendWidthBlocks = settings.blendWidthBlocks(crustSpacing);
		this.reliefVariation = FractalNoise2D.standard(
				seed ^ SALT_RELIEF, RELIEF_OCTAVES, crustSpacing * RELIEF_WAVELENGTH_FACTOR);

		// Unit wavelength: this field is fed coordinates already divided by the
		// grain's own across and along wavelengths, which is what makes the sampling
		// anisotropic. Building it at a single wavelength would force both axes to
		// share one and there would be no grain.
		this.grain = FractalNoise2D.standard(
				seed ^ SALT_GRAIN, settings.grain().octaves(), 1.0);

		this.sutureBelt = FractalNoise2D.standard(
				seed ^ SALT_SUTURE_BELT, SUTURE_BELT_OCTAVES,
				crustSpacing * SUTURE_BELT_WAVELENGTH_FACTOR);
	}

	/**
	 * Total relief at a position, combined over every plate boundary within reach.
	 *
	 * <p>Combining rather than taking the nearest boundary alone is a correctness fix,
	 * not a refinement. Where the nearest differing plate changes, the bisector jumps
	 * and so does the distance, so a nearest-only range went from full height to
	 * nothing between adjacent columns: cross sections showed vertical walls a
	 * thousand blocks tall.
	 *
	 * <p><b>A sum, divided by whichever is larger: the strongest falloff, or the
	 * total weight.</b> One expression, because three separate requirements turn out
	 * to be the same requirement, and each of the simpler forms fails one of them.
	 *
	 * <p>A plain sum is unbounded: three collision ranges meeting at a triple
	 * junction add to 4,200 blocks and terrain reached y=5,358 against a ceiling of
	 * 1,792. Dividing by the total weight fixes that and creates a worse problem,
	 * because an average is scale invariant. A margin whose weight has fallen to a
	 * ten thousandth still yields its full profile, since the weight cancels top and
	 * bottom. Measured: relief held 1,248 blocks right up to the instant such a
	 * margin was excluded, then fell to zero in a single block. That is the same
	 * cliff as before, wearing an average's clothes.
	 *
	 * <p>Scaling the average by a second falloff was the other attempt, and it
	 * applies the range's own shape twice. Every profile in {@link #profile} already
	 * reaches zero by the half-width, so multiplying by a falloff over that same
	 * width halved the height of every flank in the world.
	 *
	 * <p>The larger of the two denominators does all of it. Where one margin
	 * dominates, the strongest falloff wins and relief is that margin's profile
	 * undiminished. Where several overlap, their weights sum past it and the result
	 * becomes an average, bounded by the tallest single contribution. Where the only
	 * margin in reach is barely a margin, the weight is small but the divisor is not,
	 * so it builds a fraction of its profile rather than all of it. And a margin
	 * leaving the set does so with a falloff of zero, contributing nothing anywhere.
	 */
	/**
	 * Everything one margin search yields.
	 *
	 * @param nearest boundary nearest this position, for callers naming the margin
	 * @param relief  height offset in blocks, combined over every boundary in reach
	 * @param base    crust base elevation, already blended across cell seams
	 */
	public record Result(PlateSample nearest, double relief, double base) {
	}

	/**
	 * Evaluates relief and reports the nearest boundary, in a single pass.
	 *
	 * <p>Both in one call because the search is the expensive part of a terrain
	 * lookup and asking for them separately ran it twice: {@code plates.sample} and
	 * {@code forEachBoundary} resolve the same 25 candidate cells through the same
	 * weighted nuclei search.
	 */
	public Result evaluate(final PlateMap plates, final double worldX, final double worldZ) {
		// An array because a lambda cannot close over mutable locals: weighted relief,
		// total weight, and the strongest falloff.
		double[] acc = new double[3];

		PlateMap.Boundaries margins = plates.forEachBoundary(
				worldX, worldZ, rangeWidthBlocks, blendWidthBlocks, boundary -> {
			RangeType type = RangeType.of(boundary);
			double falloff = domeAt(boundary.boundaryDistance(), reach(type));

			if (falloff <= 0.0) {
				return;
			}

			// A worn-out suture loses its say as well as its height. Zero relief is
			// not the same as no feature: a dead seam admitted at full weight would
			// take a share of the blend from the live margin next to it and drag a
			// range down toward nothing for no reason anyone could point at.
			double presence = type == RangeType.FOSSIL_SUTURE ? survival(boundary) : 1.0;

			double weight = falloff * boundary.weight() * presence;

			if (weight <= 0.0) {
				return;
			}

			acc[0] += weight * reliefAt(boundary, worldX, worldZ);
			acc[1] += weight;

			// Tracked without the margin's own weight, deliberately. This is the
			// divisor that keeps a barely-there margin from building a whole range,
			// so it must not shrink along with the thing it is meant to restrain.
			acc[2] = Math.max(acc[2], falloff);
		});

		return new Result(
				margins.nearest(),
				acc[2] <= 0.0 ? 0.0 : acc[0] / Math.max(acc[2], acc[1]),
				margins.crustBase());
	}

	/**
	 * What this margin builds here: its profile, scaled by how hard it is working.
	 *
	 * <p>Kept apart from the blend weight in {@link #evaluate} so several margins can
	 * be weighed against each other. Folding the weight in here instead would let a
	 * distant margin count for as much as the one underfoot.
	 */
	private double reliefAt(final PlateSample sample, final double worldX, final double worldZ) {
		RangeType type = RangeType.of(sample);
		double peak = profile(type, sample);

		if (peak == 0.0) {
			return 0.0;
		}

		// Relative motion scales relief: plates barely converging build barely
		// anything. Magnitude is bounded by construction, so this stays in [0, 1].
		//
		// A fossil suture is exempt, and has to be. The two cells belong to one plate
		// and so have no relative motion at all, which is the definition of the type
		// rather than a reason to build nothing. Left in, it multiplied every suture
		// in the world by zero: the census reported plate interiors at a mean of -45
		// with no relief anywhere, unchanged from before they existed. Age already
		// does this job for them, through the weight.
		double motion = type == RangeType.FOSSIL_SUTURE
				? 1.0
				: Math.min(1.0, Math.abs(sample.convergence()) + sample.shear());

		// Vary height along the range so it is not a uniform wall. Without this a
		// mountain range is the same height for its entire length, which reads as
		// artificial more immediately than almost anything else.
		double variation = 1.0 + reliefVariation.sample(worldX, worldZ)
				* settings.reliefVariationFraction();

		return peak * motion * Math.max(0.0, variation) * grainFactor(type, sample);
	}

	/**
	 * Carves parallel ridges and valleys into the range envelope.
	 *
	 * <p>Sampled in the boundary's own frame with the across-axis compressed and the
	 * along-axis stretched, so every feature comes out {@code alongFactor} times
	 * longer than it is wide and aligned with the range. This is the entire mechanism:
	 * there is no ridge generator, only ordinary noise read through an anisotropic
	 * coordinate system.
	 *
	 * <p>Returns a multiplier in {@code [1 - depth, 1]}, so crests keep the full
	 * envelope height and valley floors drop to a fraction of it. Multiplicative
	 * rather than additive because a valley should cut proportionally: a 200-block
	 * range gets 200-block-scale valleys and a 1,400-block range gets deep ones,
	 * which is the erosion argument for scaling relief with local relief.
	 */
	private double grainFactor(final RangeType type, final PlateSample sample) {
		var g = settings.grain();

		// Signed across, not the magnitude. Reading the magnitude mirrored the grain
		// about the crest, so every range had matching ridges on both flanks.
		double ridged = 1.0 - Math.abs(grain.sample(
				sample.across() / g.acrossWavelength(),
				sample.alongBoundary() / g.alongWavelength()));

		double crest = Math.pow(Math.max(0.0, ridged), GRAIN_SHARPNESS);

		double depth = type == RangeType.FOSSIL_SUTURE
				? Math.min(1.0, g.depth() * SUTURE_GRAIN_DEPTH)
				: g.depth();

		return 1.0 - depth * (1.0 - crest);
	}

	/**
	 * The shape of this range, read at the query's signed distance from the margin.
	 *
	 * <p>Returns blocks, before motion scaling and grain. Sign convention: positive
	 * rises, negative drops.
	 *
	 * <p><b>One function of one signed variable, per range type.</b> That is the
	 * whole design, and it is what the previous form could not do. Choosing a peak
	 * from the crust the query happened to be standing on meant the two flanks of a
	 * subduction margin evaluated different expressions at the same point, so the
	 * surface stepped by the difference between an arc and a trench all along it.
	 * Orientation now comes from the pair, through
	 * {@link PlateSample#landwardSign()} or the margin's own hash, so a flank knows
	 * which flank it is without asking where the question came from, and continuity
	 * at the margin is a property of the expression rather than something to check.
	 */
	private double profile(final RangeType type, final PlateSample sample) {
		double across = sample.across();

		return switch (type) {
			// Symmetric: one continent against another, with nothing to distinguish
			// the flanks until deposition can build the foreland basin on one of them.
			case CONTINENTAL_COLLISION ->
					settings.continentalCollisionRise() * dome(Math.abs(across));

			case SUBDUCTION_ARC -> arcProfile(
					across * sample.landwardSign(),
					settings.subductionArcRise(), settings.trenchDrop());

			// Both halves are ocean, so there is no crust type to orient by. The
			// margin's own hash picks the polarity, which keeps it a property of the
			// pair and therefore identical from both sides.
			case ISLAND_ARC -> arcProfile(
					across * (((sample.marginId() >>> 17) & 1L) == 0L ? 1 : -1),
					settings.oceanicArcRise(), settings.trenchDrop());

			case CONTINENTAL_RIFT -> riftProfile(across);

			case OCEANIC_RIDGE -> settings.oceanicRidgeRise() * dome(Math.abs(across));

			case TRANSFORM -> settings.transformRelief() * dome(Math.abs(across));

			// A worn-down collision: the same symmetric dome, lower. Age is not applied
			// here. It scales this margin's weight in evaluate instead, which decays
			// the height by exactly the same factor while also taking away the
			// influence of a seam that is no longer a feature. Applying it in both
			// places squares it.
			case FOSSIL_SUTURE -> settings.fossilSutureRise()
					* domeAt(Math.abs(across), reach(RangeType.FOSSIL_SUTURE));

			// A quiet coast builds nothing, and that is the whole of its character.
			case PASSIVE_MARGIN -> 0.0;
		};
	}

	/**
	 * A trench at the contact and a wall rising behind it, in one expression.
	 *
	 * @param landward signed distance from the margin, negative out to sea
	 */
	private double arcProfile(
			final double landward, final double arcRise, final double trenchDrop) {
		return trenchDrop * bump(landward, TRENCH_CENTRE, TRENCH_WIDTH)
				+ arcRise * bump(landward, ARC_CENTRE, ARC_WIDTH);
	}

	/**
	 * A dropped floor between two raised shoulders.
	 *
	 * <p>Symmetric, so orientation does not arise. The shape is the point: a rift is
	 * the one margin where the ground at the boundary is lower than the ground on
	 * either side of it, and no single peak height can say that. The shoulders are
	 * what make the East African Rift and the Dead Sea read as rifts rather than as
	 * dents.
	 */
	private double riftProfile(final double across) {
		double magnitude = Math.abs(across);

		return settings.continentalRiftDrop() * bump(magnitude, 0.0, RIFT_FLOOR_WIDTH)
				+ settings.riftShoulderRise() * bump(magnitude, RIFT_SHOULDER_CENTRE, RIFT_SHOULDER_WIDTH);
	}

	/**
	 * A smooth hump centred somewhere along the across-axis.
	 *
	 * <p>Centre and width are fractions of the range half-width, so every profile
	 * rescales with the range rather than needing its own absolute distances.
	 */
	private double bump(final double across, final double centre, final double width) {
		return domeAt(
				Math.abs(across - centre * rangeWidthBlocks), width * rangeWidthBlocks);
	}

	/** The standard hump, one range half-width wide. */
	private double dome(final double distance) {
		return domeAt(distance, rangeWidthBlocks);
	}

	/**
	 * How far this type of range reaches.
	 *
	 * <p>Used for the profile and for the blend weight alike, and it has to be both.
	 * A margin that stopped building at one distance while still claiming influence
	 * out to another would take a share of the blend from its neighbours across
	 * ground it does nothing to.
	 */
	private double reach(final RangeType type) {
		return type == RangeType.FOSSIL_SUTURE
				? rangeWidthBlocks * SUTURE_WIDTH_FACTOR
				: rangeWidthBlocks;
	}

	/**
	 * How much of a fossil suture is left, in {@code [0, 1]}.
	 *
	 * <p>Three factors, and the first two are what stop sutures from being everywhere.
	 *
	 * <p><b>Continental crust only.</b> Ocean floor is young and recycled; it has no
	 * finished collisions in it because nothing that old survives there. Without this
	 * test every ocean basin in the world was lifted, and land went from 33.9 percent
	 * of the map to 60.2.
	 *
	 * <p><b>A belt, not a lottery per seam.</b> Sutures are linear because they trace
	 * one collision across a continent, so the decision is read from a low-frequency
	 * field at the margin's own midpoint rather than hashed per seam. Adjacent seams
	 * then agree, and what appears is a band of worn ridges running for tens of
	 * thousands of blocks with quiet ground either side. Hashing each seam
	 * independently gives isolated fragments and, with 6,000-block cells, corrugates
	 * an entire continent: measured as roughly 50 blocks of relief added to the far
	 * bins of every other range type in the world.
	 *
	 * <p><b>Then age.</b> Drawn toward the old end, so even inside a belt the seams
	 * vary from a respectable range down to nothing.
	 *
	 * <p>Scales influence as well as height, so a suture worn to nothing has no say in
	 * what its neighbours look like.
	 */
	private double survival(final PlateSample sample) {
		if (!sample.lowCrust().isContinental() || !sample.highCrust().isContinental()) {
			return 0.0;
		}

		double midX = (sample.lowCrust().siteX() + sample.highCrust().siteX()) * 0.5;
		double midZ = (sample.lowCrust().siteZ() + sample.highCrust().siteZ()) * 0.5;

		// The belt decides where, and saturates. Letting its value scale the height
		// as well made every suture a fraction of one: the field rarely runs far past
		// its own threshold, so the tallest suture in the world reached y=118 against
		// a setting of 300. Age is what decides how much survives.
		double belt = smoothstep(
				(sutureBelt.sampleUnit(midX, midZ) - SUTURE_BELT_THRESHOLD) / SUTURE_BELT_SOFTNESS);

		if (belt <= 0.0) {
			return 0.0;
		}

		double age = Hashing.unitDouble(
				sample.marginId(), sample.lowCrust().cellX(), sample.lowCrust().cellZ(),
				SALT_SUTURE_AGE);

		return belt * Math.pow(1.0 - age, SUTURE_AGE_FALLOFF);
	}

	/**
	 * Smooth hump of a given width, reaching zero at its edge.
	 *
	 * <p>Uses the complement of smoothstep, whose derivative is zero at both ends. A
	 * linear falloff would leave a visible crease where a feature meets the plain,
	 * and a sharp peak at the centre would produce a razor edge rather than a crest.
	 *
	 * <p>The one shape every profile here is assembled from. Giving each range type
	 * its own curve would put the burden of continuity on each of them separately;
	 * summing humps that are each smooth and each reach zero means a profile cannot
	 * be discontinuous however the humps are arranged.
	 */
	private static double smoothstep(final double x) {
		double t = Math.max(0.0, Math.min(1.0, x));

		return t * t * (3.0 - 2.0 * t);
	}

	private static double domeAt(final double distance, final double width) {
		if (distance >= width) {
			return 0.0;
		}

		double t = distance / width;

		return 1.0 - (t * t * (3.0 - 2.0 * t));
	}

	/**
	 * How much say this margin has here, and how far its influence reaches.
	 *
	 * <p>Distinct from the profile now. This decides the blend between margins that
	 * overlap; the profile decides what each one builds.
	 */
	private double falloff(final double boundaryDistance) {
		return dome(boundaryDistance);
	}

}
