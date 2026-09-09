package com.fury.terramax.core.plate;

import com.fury.terramax.core.util.DomainWarp;
import com.fury.terramax.core.util.FractalNoise2D;
import com.fury.terramax.core.util.Hashing;
import com.fury.terramax.core.util.PoissonDisk;
import com.fury.terramax.core.util.VoronoiSample;
import com.fury.terramax.core.util.VoronoiSolver;
import com.fury.terramax.core.util.WeightedVoronoi;

/**
 * Resolves world positions to plates, and classifies what happens where plates
 * meet.
 *
 * <p><b>No cache, deliberately.</b> The design document specified a
 * {@code PlateMapCache} persisting 512x512 regions to save data, because it
 * assumed Bridson's Poisson disk sampling, which has to generate a whole region
 * in sequence. This uses a stateless jittered grid instead, so a lookup is a few
 * dozen hashes with no allocation and no ordering dependency. Caching that would
 * cost more in memory and save-format compatibility than it saves in arithmetic.
 * If profiling later disagrees, a cache can be added in front of this class
 * without changing anything that calls it.
 *
 * <p><b>On warped space.</b> Queries are displaced by {@link DomainWarp} before
 * the lattice is consulted, which is what turns straight Voronoi edges into
 * plausible coastlines. Everything downstream, including
 * {@link PlateSample#boundaryDistance()}, is therefore measured in warped space.
 * At the default warp strength the distortion is minor, but a very strong warp
 * will make boundary distances, and any mountain profile derived from them,
 * uneven along a range.
 */
public final class PlateMap {
	/** Decorrelates the several independent draws made per plate. */
	private static final long SALT_ELEVATION = 12L;
	private static final long SALT_MOTION_ANGLE = 13L;
	private static final long SALT_MOTION_SPEED = 14L;

	/** Separates the continent field from the warp fields. */
	private static final long SALT_CONTINENT = 0x6A09E667F3BCC909L;

	/** Keeps the nuclei lattice's jitter independent of the crust lattice's. */
	private static final long SALT_NUCLEI = 0x8EBC6AF09C88C6E3L;

	/**
	 * Slowest a plate may move, as a fraction of the fastest.
	 *
	 * <p>Not zero: a stationary plate would classify every one of its boundaries
	 * purely by its neighbours' motion, which produces implausibly uniform results
	 * around it.
	 */
	private static final double MIN_MOTION_SPEED = 0.35;

	/** Octaves in the land/ocean field. Low, because continents are broad shapes. */
	private static final int CONTINENT_OCTAVES = 2;

	/**
	 * How far apart two crust sites may be and still be treated as a possible margin,
	 * in crust spacings.
	 *
	 * <p>Only a cheap rejection. Cells further apart than this cannot share a Voronoi
	 * edge near the query, and the third-cell weight would give them zero anyway; the
	 * test exists so most of the several hundred candidate pairs are dismissed on one
	 * subtraction instead of a full scan.
	 */
	private static final double MAX_PAIR_SEPARATION_FACTOR = 2.2;

	/**
	 * Distance over which a margin fades out as a third cell takes over, in crust
	 * spacings.
	 *
	 * <p>This is the width of a triple junction. Too small and a range ends abruptly
	 * where it meets another, which is the cliff this whole enumeration exists to
	 * remove; too large and every margin is weakened by its neighbours everywhere
	 * along its length, so ranges never reach full height.
	 */
	private static final double JUNCTION_SOFTNESS_FACTOR = 0.12;

	/** Rings scanned for the third cell that ends a margin. One past the pair search. */
	private static final int JUNCTION_SCAN_EXTRA_CELLS = 1;

	/**
	 * Samples per axis used to calibrate the land/ocean threshold. 64x64 is 4096
	 * evaluations, a few milliseconds once, and enough for a stable quantile.
	 */
	private static final int CALIBRATION_GRID = 64;

	/** Area calibrated over, in crust spacings. Wide enough to span many continents. */
	private static final double CALIBRATION_SPAN_FACTOR = 640.0;

	private final long seed;
	private final PlateMapSettings settings;
	private final VoronoiSolver voronoi;
	private final WeightedVoronoi nuclei;
	private final DomainWarp warp;
	private final FractalNoise2D continentField;
	private final double continentThreshold;
	private final ThreadLocal<Window> scratch;

	public PlateMap(final long seed, final PlateMapSettings settings) {
		this.seed = seed;
		this.settings = settings;
		this.voronoi = new VoronoiSolver(
				new PoissonDisk(seed, settings.crustSpacingBlocks(), settings.jitter()));
		this.nuclei = new WeightedVoronoi(
				new PoissonDisk(seed ^ SALT_NUCLEI, settings.nucleiSpacingBlocks(), settings.jitter()),
				seed ^ SALT_NUCLEI,
				settings.nucleiMaxWeightBlocks());
		this.warp = new DomainWarp(
				seed,
				settings.warpStrengthBlocks(),
				settings.warpWavelengthBlocks(),
				settings.warp().octaves());
		this.continentField = FractalNoise2D.standard(
				seed ^ SALT_CONTINENT, CONTINENT_OCTAVES, settings.continentWavelengthBlocks());
		this.continentThreshold = calibrateContinentThreshold();

		int pairRadius = voronoi.searchRadiusCells();
		int scanRadius = pairRadius + JUNCTION_SCAN_EXTRA_CELLS;

		this.scratch = ThreadLocal.withInitial(() -> new Window(pairRadius, scanRadius));
	}

	/**
	 * Finds the field value below which {@code continentalFraction} of the world
	 * lies.
	 *
	 * <p>Thresholding fractal noise directly does not give the proportion you asked
	 * for. Its output clusters near the middle of its range rather than spreading
	 * uniformly, so a cut at 0.62 claimed 78% of the area in testing. Taking the
	 * actual quantile of a sampled grid makes {@code continentalFraction} mean what
	 * its name says.
	 *
	 * <p>Deterministic: the same seed and settings always calibrate identically, so
	 * the mod and the simulator agree.
	 */
	private double calibrateContinentThreshold() {
		double[] samples = new double[CALIBRATION_GRID * CALIBRATION_GRID];
		double span = settings.crustSpacingBlocks() * CALIBRATION_SPAN_FACTOR;
		double step = span / CALIBRATION_GRID;

		int index = 0;

		for (int gz = 0; gz < CALIBRATION_GRID; gz++) {
			for (int gx = 0; gx < CALIBRATION_GRID; gx++) {
				samples[index++] = continentField.sampleUnit(gx * step, gz * step);
			}
		}

		java.util.Arrays.sort(samples);

		int rank = (int) Math.round(settings.continentalFraction() * (samples.length - 1));

		return samples[Math.max(0, Math.min(samples.length - 1, rank))];
	}

	public PlateMapSettings settings() {
		return settings;
	}

	public VoronoiSolver voronoi() {
		return voronoi;
	}

	public WeightedVoronoi nuclei() {
		return nuclei;
	}

	public DomainWarp warp() {
		return warp;
	}

	/**
	 * The crust cell with the given identity. Pure function of seed and coordinate.
	 *
	 * <p>Crust type comes from a low-frequency field sampled at the cell's site,
	 * not from an independent per-cell draw. An independent draw makes each cell
	 * flip its own coin, which mixes land and ocean uniformly and gives isolated
	 * seas ringed by land instead of continents and oceans.
	 */
	public CrustCell crustCellAt(final long cellX, final long cellZ) {
		double siteX = voronoi.sites().pointX(cellX, cellZ);
		double siteZ = voronoi.sites().pointZ(cellX, cellZ);

		CrustType type = continentField.sampleUnit(siteX, siteZ) < continentThreshold
				? CrustType.CONTINENTAL
				: CrustType.OCEANIC;

		int base = type == CrustType.CONTINENTAL
				? settings.continentalBase()
				: settings.oceanicBase();

		double variation = (Hashing.unitDouble(seed, cellX, cellZ, SALT_ELEVATION) - 0.5)
				* 2.0 * settings.baseVariation();

		return new CrustCell(cellX, cellZ, siteX, siteZ, type, base + variation);
	}

	/**
	 * The plate owning a crust cell.
	 *
	 * <p>Membership is decided by which weighted nucleus wins at the cell's
	 * <em>site</em>, not at the query position. Using the site means every point in
	 * a crust cell agrees on its plate, so plate outlines follow crust cell edges
	 * and come out ragged. Deciding per position instead would give plate
	 * boundaries their own independent smooth geometry, and the crust lattice would
	 * stop being the unit of outline granularity that it exists to be.
	 *
	 * <p>Motion is hashed from the <em>nucleus</em>, not from the crust cell, which
	 * is what makes a plate move as one body. Hashing it per cell would give every
	 * cell in a plate its own velocity, which is not a plate at all.
	 */
	public Plate plateOf(final long crustCellX, final long crustCellZ) {
		double siteX = voronoi.sites().pointX(crustCellX, crustCellZ);
		double siteZ = voronoi.sites().pointZ(crustCellX, crustCellZ);

		WeightedVoronoi.Nearest owner = nuclei.nearest(siteX, siteZ);

		double angle = Hashing.unitDouble(seed, owner.cellX(), owner.cellZ(), SALT_MOTION_ANGLE)
				* Math.TAU;
		double speed = MIN_MOTION_SPEED + (1.0 - MIN_MOTION_SPEED)
				* Hashing.unitDouble(seed, owner.cellX(), owner.cellZ(), SALT_MOTION_SPEED);

		return new Plate(
				owner.cellX(), owner.cellZ(),
				Math.cos(angle) * speed, Math.sin(angle) * speed);
	}

	/**
	 * Resolves the owning plate, the nearest plate <em>boundary</em>, and what the
	 * two plates are doing to each other there.
	 *
	 * <p><b>The neighbour is the nearest cell of a different plate, not simply the
	 * second-nearest cell.</b> This distinction is the whole correctness of the
	 * mountain system. A plate boundary is a property of the plate mosaic, but crust
	 * cells are sixteen times finer, so as you walk along a boundary the
	 * second-nearest crust cell keeps changing: sometimes it lies across the plate
	 * boundary and sometimes it is another cell of your own plate. Taking it blindly
	 * makes relief switch between full height and nothing from one cell to the next,
	 * and a range comes out as a chain of cell-shaped mesas with 1,600-block vertical
	 * walls between them. Measured, not hypothesised: it is what the first cross
	 * section through a range showed.
	 *
	 * <p>Searching for the nearest differing plate instead gives a boundary distance
	 * that varies continuously along the whole margin, which is what a falloff needs.
	 */
	public PlateSample sample(final double worldX, final double worldZ) {
		double queryX = warp.warpX(worldX, worldZ);
		double queryZ = warp.warpZ(worldX, worldZ);

		VoronoiSample cell = voronoi.sample(queryX, queryZ);

		Plate plate = plateOf(cell.cellX(), cell.cellZ());
		CrustCell crust = crustCellAt(cell.cellX(), cell.cellZ());

		Neighbour across = nearestDifferentPlate(queryX, queryZ, cell, plate);

		// Deep inside a plate there is no boundary within reach. Reporting an
		// effectively infinite distance keeps every downstream falloff at zero with
		// no special case: smoothstep clamps to 1, and MountainRidge returns 0 past
		// its range width.
		if (across == null) {
			return new PlateSample(
					plate, plate, crust, crust,
					PlateBoundaryType.NONE,
					Double.MAX_VALUE, cell.alongBoundary(),
					0.0, 0.0, 0.0, cell.cellId(seed));
		}

		return marginOf(cell, across);
	}

	/** The margin between the query's own cell and a chosen neighbour of it. */
	private PlateSample marginOf(final VoronoiSample cell, final Neighbour across) {
		boolean ownIsLow = lowerCell(
				cell.cellX(), cell.cellZ(), across.cellX(), across.cellZ());

		CrustCell own = crustCellAt(cell.cellX(), cell.cellZ());
		CrustCell other = crustCellAt(across.cellX(), across.cellZ());
		Plate ownPlate = plateOf(cell.cellX(), cell.cellZ());

		return ownIsLow
				? margin(own, other, ownPlate, across.plate(),
						across.across(), across.alongBoundary(), 1.0)
				: margin(other, own, across.plate(), ownPlate,
						across.across(), across.alongBoundary(), 1.0);
	}

	/**
	 * Works out what two plates are doing to each other across the margin between two
	 * crust cells.
	 *
	 * <p><b>Takes the pair, not a query and its neighbour.</b> Everything here is a
	 * property of the two cells: which plates own them, what those plates are doing,
	 * and the margin's own identity. Nothing depends on where the question was asked
	 * from, so two adjacent columns on opposite sides of the margin receive the same
	 * answer with only the sign of {@code across} between them.
	 *
	 * <p>Shared by {@link #sample} and {@link #forEachBoundary} so the two cannot
	 * disagree about how a margin is classified.
	 *
	 * @param across signed, negative on the low cell's side and positive on the high
	 *               cell's, with the pair ordered by {@link #lowerCell}
	 */
	private PlateSample margin(
			final Window window, final int low, final int high,
			final double across, final double alongBoundary, final double weight) {
		return margin(
				window.crustAt(this, low), window.crustAt(this, high),
				window.plateAt(this, low), window.plateAt(this, high),
				across, alongBoundary, weight);
	}

	private PlateSample margin(
			final CrustCell lowCrust, final CrustCell highCrust,
			final Plate lowPlate, final Plate highPlate,
			final double across, final double alongBoundary, final double weight) {
		long marginId = Hashing.hash(
				Hashing.hash(seed, lowCrust.cellX(), lowCrust.cellZ()),
				highCrust.cellX(), highCrust.cellZ());

		// A seam between two cells of the same plate. Not a boundary, and the
		// fossil suture it will become is a later slice.
		if (lowPlate.cellX() == highPlate.cellX() && lowPlate.cellZ() == highPlate.cellZ()) {
			return new PlateSample(
					lowPlate, highPlate, lowCrust, highCrust,
					PlateBoundaryType.NONE,
					across, alongBoundary, 0.0, 0.0, weight, marginId);
		}

		double axisX = highCrust.siteX() - lowCrust.siteX();
		double axisZ = highCrust.siteZ() - lowCrust.siteZ();
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);

		if (axisLength == 0.0) {
			return new PlateSample(
					lowPlate, highPlate, lowCrust, highCrust,
					PlateBoundaryType.TRANSFORM,
					across, alongBoundary, 0.0, 0.0, weight, marginId);
		}

		// Unit normal pointing from the low cell toward the high one, and the
		// tangent along the margin.
		double normalX = axisX / axisLength;
		double normalZ = axisZ / axisLength;

		// Relative motion of the low plate with respect to the high one. Both the
		// normal and the pair order come from the canonical ordering rather than from
		// the query, so this is one number belonging to the margin.
		double relativeX = lowPlate.motionX() - highPlate.motionX();
		double relativeZ = lowPlate.motionZ() - highPlate.motionZ();

		double convergence = relativeX * normalX + relativeZ * normalZ;
		double shear = Math.abs(relativeX * -normalZ + relativeZ * normalX);

		// Transform only where shear clearly dominates. A plain `shear > |convergence|`
		// test makes half of all boundaries transform, since the relative motion
		// direction is uniform. Transform margins build almost no relief, so that would
		// leave most of the world's boundaries producing nothing.
		PlateBoundaryType type;

		if (shear > settings.transformDominance() * Math.abs(convergence)) {
			type = PlateBoundaryType.TRANSFORM;
		} else {
			type = convergence > 0.0 ? PlateBoundaryType.CONVERGENT : PlateBoundaryType.DIVERGENT;
		}

		return new PlateSample(
				lowPlate, highPlate, lowCrust, highCrust,
				type, across, alongBoundary, convergence, shear, weight, marginId);
	}

	/**
	 * True where the first cell sorts before the second.
	 *
	 * <p>The one rule that makes a margin an object rather than a point of view. Both
	 * sides order the pair the same way, so both build the same frame, agree on the
	 * sign of the across-axis and hash to the same identity.
	 */
	private static boolean lowerCell(
			final long cellX, final long cellZ, final long otherX, final long otherZ) {
		return cellX < otherX || (cellX == otherX && cellZ < otherZ);
	}

	/**
	 * Builds the boundary frame between the query's own cell and a given other cell.
	 *
	 * <p>Orders the pair canonically, so both sides of a boundary compute the same
	 * frame and grain built on it does not mirror across the crest.
	 */
	private Neighbour frameAgainst(
			final double queryX, final double queryZ, final VoronoiSample own,
			final long otherCellX, final long otherCellZ, final Plate otherPlate) {
		double otherSiteX = voronoi.sites().pointX(otherCellX, otherCellZ);
		double otherSiteZ = voronoi.sites().pointZ(otherCellX, otherCellZ);

		boolean flip = own.cellX() > otherCellX
				|| (own.cellX() == otherCellX && own.cellZ() > otherCellZ);

		double aX = flip ? otherSiteX : own.siteX();
		double aZ = flip ? otherSiteZ : own.siteZ();
		double bX = flip ? own.siteX() : otherSiteX;
		double bZ = flip ? own.siteZ() : otherSiteZ;

		double axisX = bX - aX;
		double axisZ = bZ - aZ;
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);

		if (axisLength == 0.0) {
			return new Neighbour(
					otherCellX, otherCellZ, otherSiteX, otherSiteZ, otherPlate, 0.0, 0.0);
		}

		double offsetX = queryX - (aX + bX) * 0.5;
		double offsetZ = queryZ - (aZ + bZ) * 0.5;

		// Signed, not absolute. The axis runs from the canonically first cell to the
		// second, so the sign says which half of the pair the query stands on, which
		// is what an asymmetric range needs and what taking the magnitude threw away.
		return new Neighbour(
				otherCellX, otherCellZ, otherSiteX, otherSiteZ, otherPlate,
				(offsetX * axisX + offsetZ * axisZ) / axisLength,
				(offsetX * -axisZ + offsetZ * axisX) / axisLength);
	}

	/** Receives one boundary at a time from {@link #forEachBoundary}. */
	@FunctionalInterface
	public interface BoundaryVisitor {
		void visit(PlateSample boundary);
	}

	/**
	 * What one pass over the margins around a position yields besides the margins.
	 *
	 * @param nearest   nearest boundary between differing plates, for callers that
	 *                  want to know which margin they are on rather than what every
	 *                  margin builds
	 * @param crustBase crust base elevation here, blended across cell seams
	 */
	public record Boundaries(PlateSample nearest, double crustBase) {
	}

	/**
	 * Visits every margin within reach of a position, not just the nearest.
	 *
	 * <p><b>Margins are enumerated as pairs of crust cells, chosen by geometry.</b>
	 * The previous version paired the query's own cell with each of its neighbours,
	 * and that is what put walls through the world's mountains. The set of pairs then
	 * depends on which cell the query landed in, so stepping across any bisector
	 * replaces all of them at once: the pair that was building a range stops existing
	 * and a different pair appears in its place, at full strength, building something
	 * else. Nothing fades. Measured on an eight-transect walk at one-block steps, the
	 * surface stepped 1,323 blocks between two adjacent columns, and cross sections
	 * showed four vertical walls in a single 12,000-block line.
	 *
	 * <p>The fix is to ask a question that has nothing to do with the query's cell:
	 * which pairs of cells share a Voronoi edge near this point? A pair is admitted
	 * when its bisector passes within {@code maxDistance} and when that stretch of
	 * bisector is a real edge rather than a line through some third cell's territory.
	 * The second test is a matter of degree rather than a yes or no, and its answer is
	 * {@link PlateSample#weight()}, which falls smoothly to zero at the triple
	 * junctions where an edge ends. So a margin enters and leaves the set at zero on
	 * both counts, by distance and by weight, and neither its arrival nor its
	 * departure can be seen in the terrain.
	 *
	 * <p>Triple junctions come out right as a consequence rather than as a special
	 * case. Three margins meet, all three are visited, all three are fading, and the
	 * ground there is the blend of what all three are doing, which is why real
	 * junctions are structurally incoherent.
	 *
	 * @param maxDistance    margins further than this contribute nothing, so are skipped
	 * @param baseBlendWidth distance over which neighbouring crust bases blend into
	 *                       each other, for the returned {@code crustBase}
	 */
	public Boundaries forEachBoundary(
			final double worldX, final double worldZ,
			final double maxDistance, final double baseBlendWidth,
			final BoundaryVisitor visitor) {
		double queryX = warp.warpX(worldX, worldZ);
		double queryZ = warp.warpZ(worldX, worldZ);

		VoronoiSample cell = voronoi.sample(queryX, queryZ);
		Plate plate = plateOf(cell.cellX(), cell.cellZ());
		CrustCell crust = crustCellAt(cell.cellX(), cell.cellZ());

		Window window = scratch.get();
		window.fill(voronoi.sites(), queryX, queryZ, maxDistance);

		double separationLimit = settings.crustSpacingBlocks() * MAX_PAIR_SEPARATION_FACTOR;
		double separationLimitSq = separationLimit * separationLimit;
		double softness = settings.crustSpacingBlocks() * JUNCTION_SOFTNESS_FACTOR;

		for (int i = 0; i < window.candidateCount; i++) {
			for (int j = i + 1; j < window.candidateCount; j++) {
				int a = window.candidates[i];
				int b = window.candidates[j];

				double axisX = window.siteX[b] - window.siteX[a];
				double axisZ = window.siteZ[b] - window.siteZ[a];
				double separationSq = axisX * axisX + axisZ * axisZ;

				// Squared, so the overwhelming majority of pairs are rejected before
				// anything has to take a square root.
				if (separationSq > separationLimitSq || separationSq == 0.0) {
					continue;
				}

				int low = lowerCell(window.cellX[a], window.cellZ[a],
						window.cellX[b], window.cellZ[b]) ? a : b;
				int high = low == a ? b : a;

				if (low != a) {
					axisX = -axisX;
					axisZ = -axisZ;
				}

				double separation = Math.sqrt(separationSq);

				// Signed distance to the bisector, from the difference of squared
				// distances. Positive on the high cell's side.
				double across = (window.distSq[low] - window.distSq[high]) / (2.0 * separation);

				if (Math.abs(across) >= maxDistance) {
					continue;
				}

				double weight = window.marginWeight(low, high, queryX, queryZ,
						across, axisX / separation, axisZ / separation, softness);

				if (weight <= 0.0) {
					continue;
				}

				double offsetX = queryX - (window.siteX[low] + window.siteX[high]) * 0.5;
				double offsetZ = queryZ - (window.siteZ[low] + window.siteZ[high]) * 0.5;

				PlateSample boundary = margin(window, low, high,
						across,
						(offsetX * -axisZ + offsetZ * axisX) / separation,
						weight);

				// Seams inside one plate build nothing yet, and averaging them in
				// would dilute the margins that do. Fossil sutures are a later slice.
				if (boundary.boundaryType() != PlateBoundaryType.NONE) {
					visitor.visit(boundary);
				}
			}
		}

		// The nearest boundary is still found the old way, and deliberately. It feeds
		// the crust base blend, which reaches further than any range and wants the
		// nearest cell of a *different plate* rather than the nearest margin of any
		// kind. Relief no longer uses it at all.
		Neighbour nearest = nearestDifferentPlate(queryX, queryZ, cell, plate);
		double crustBase = window.blendedBase(this, baseBlendWidth);

		if (nearest == null) {
			return new Boundaries(
					new PlateSample(
							plate, plate, crust, crust,
							PlateBoundaryType.NONE,
							Double.MAX_VALUE, cell.alongBoundary(),
							0.0, 0.0, 0.0, cell.cellId(seed)),
					crustBase);
		}

		return new Boundaries(marginOf(cell, nearest), crustBase);
	}

	/**
	 * The crust sites around one query, held so a pair search can reuse them.
	 *
	 * <p>Two radii, and the difference between them is what keeps margin weights
	 * continuous. Pairs are drawn from the inner ring, which is the same
	 * neighbourhood the Voronoi search uses and reaches well past the widest range.
	 * The third-cell test that fades a margin out at a triple junction scans the
	 * outer ring, one further. A site entering or leaving the window does so at the
	 * outer edge, far enough away that it can never be the nearest cell to any
	 * bisector the inner ring produced, so the weight it would have contributed is
	 * zero either way and the window's own lattice leaves no trace in the terrain.
	 *
	 * <p>Held per thread rather than allocated per call. A query runs this for every
	 * column of every chunk, and the arrays are the same size every time.
	 */
	private static final class Window {
		private final int pairCount;
		private final int scanCount;
		private final long[] cellX;
		private final long[] cellZ;
		private final double[] siteX;
		private final double[] siteZ;
		private final double[] distSq;
		private final Plate[] plate;
		private final CrustCell[] crust;

		/** Indices into the inner ring that could still form a margin. */
		private final int[] candidates;
		private int candidateCount;

		private Window(final int pairRadius, final int scanRadius) {
			int pairSide = pairRadius * 2 + 1;
			int scanSide = scanRadius * 2 + 1;

			this.pairCount = pairSide * pairSide;
			this.scanCount = scanSide * scanSide;
			this.cellX = new long[scanCount];
			this.cellZ = new long[scanCount];
			this.siteX = new double[scanCount];
			this.siteZ = new double[scanCount];
			this.distSq = new double[scanCount];
			this.plate = new Plate[scanCount];
			this.crust = new CrustCell[scanCount];
			this.candidates = new int[pairCount];

			// The inner ring occupies the first pairCount slots, so a pair loop can
			// stop early and the scan loop can run to the end of the same arrays.
			this.order = new int[scanCount][2];

			int at = 0;

			for (int dz = -pairRadius; dz <= pairRadius; dz++) {
				for (int dx = -pairRadius; dx <= pairRadius; dx++) {
					order[at][0] = dx;
					order[at][1] = dz;
					at++;
				}
			}

			for (int dz = -scanRadius; dz <= scanRadius; dz++) {
				for (int dx = -scanRadius; dx <= scanRadius; dx++) {
					if (Math.abs(dx) <= pairRadius && Math.abs(dz) <= pairRadius) {
						continue;
					}

					order[at][0] = dx;
					order[at][1] = dz;
					at++;
				}
			}
		}

		private final int[][] order;

		/**
		 * Loads the sites around a query and works out which can form a margin.
		 *
		 * <p><b>The candidate list is a bound, not a heuristic.</b> A pair only
		 * survives if some third cell is not closer to the foot of the perpendicular,
		 * and that is impossible once a cell lies further than the nearest cell plus
		 * twice the reach: the foot is within {@code maxDistance} of the query, so the
		 * nearest cell is at most {@code nearest + maxDistance} from it while such a
		 * far cell is at least {@code distance - maxDistance}. Cells beyond the bound
		 * are dropped before the pair loop rather than after, which turns several
		 * hundred candidate pairs into a few dozen without changing one column of the
		 * result.
		 */
		private void fill(
				final PoissonDisk sites, final double queryX, final double queryZ,
				final double maxDistance) {
			long centreX = sites.cellX(queryX);
			long centreZ = sites.cellZ(queryZ);

			double nearestSq = Double.MAX_VALUE;

			for (int i = 0; i < scanCount; i++) {
				long x = centreX + order[i][0];
				long z = centreZ + order[i][1];

				double pointX = sites.pointX(x, z);
				double pointZ = sites.pointZ(x, z);
				double offsetX = pointX - queryX;
				double offsetZ = pointZ - queryZ;

				cellX[i] = x;
				cellZ[i] = z;
				siteX[i] = pointX;
				siteZ[i] = pointZ;
				distSq[i] = offsetX * offsetX + offsetZ * offsetZ;
				plate[i] = null;
				crust[i] = null;

				if (i < pairCount) {
					nearestSq = Math.min(nearestSq, distSq[i]);
				}
			}

			double reach = Math.sqrt(nearestSq) + 2.0 * maxDistance;
			double reachSq = reach * reach;

			candidateCount = 0;

			for (int i = 0; i < pairCount; i++) {
				if (distSq[i] <= reachSq) {
					candidates[candidateCount++] = i;
				}
			}
		}

		/** The plate owning a window cell, resolved once per query rather than per pair. */
		private Plate plateAt(final PlateMap plates, final int index) {
			if (plate[index] == null) {
				plate[index] = plates.plateOf(cellX[index], cellZ[index]);
			}

			return plate[index];
		}

		/** The crust cell at a window index, resolved once per query rather than per pair. */
		private CrustCell crustAt(final PlateMap plates, final int index) {
			if (crust[index] == null) {
				crust[index] = plates.crustCellAt(cellX[index], cellZ[index]);
			}

			return crust[index];
		}

		/**
		 * How much a pair of cells really is the margin here, in {@code [0, 1]}.
		 *
		 * <p>Two cells define a bisector everywhere, but they only share a Voronoi
		 * edge along the stretch where no third cell is closer. Beyond its ends the
		 * bisector is a line through someone else's territory and must build nothing.
		 *
		 * <p>Measured at the <b>foot of the perpendicular</b>, the point on the
		 * bisector nearest the query, rather than at the query itself. At the query,
		 * a third cell is closer than the far member of the pair over most of a
		 * range's width, which would delete every flank. At the foot, the comparison
		 * asks the question that was actually meant: is this stretch of bisector a
		 * real edge? The answer varies smoothly along the edge and reaches zero at
		 * the triple junctions that end it, so a range fades out where it should
		 * rather than stopping.
		 */
		private double marginWeight(
				final int low, final int high,
				final double queryX, final double queryZ, final double across,
				final double normalX, final double normalZ, final double softness) {
			double footX = queryX - across * normalX;
			double footZ = queryZ - across * normalZ;

			double toLowX = footX - siteX[low];
			double toLowZ = footZ - siteZ[low];
			double pairSq = toLowX * toLowX + toLowZ * toLowZ;

			double otherSq = Double.MAX_VALUE;

			for (int i = 0; i < scanCount; i++) {
				if (i == low || i == high) {
					continue;
				}

				double offsetX = footX - siteX[i];
				double offsetZ = footZ - siteZ[i];

				otherSq = Math.min(otherSq, offsetX * offsetX + offsetZ * offsetZ);
			}

			return smoothstep((Math.sqrt(otherSq) - Math.sqrt(pairSq)) / softness);
		}

		/**
		 * Crust base elevation here, blended smoothly across every cell seam.
		 *
		 * <p><b>Not the owning cell's base graded toward one neighbour.</b> That was
		 * the previous form, and it graded toward the nearest cell of a different
		 * <i>plate</i>, which meant a seam between two cells of the same plate was
		 * not blended at all. Where such a seam separates continental crust from
		 * oceanic, and it often does because crust type is decided per cell and
		 * independently of plate membership, the ground stepped by the whole
		 * difference between a continental base and an oceanic one. Measured at 118
		 * blocks, standing as a vertical wall along a coastline that the design
		 * intends to be the quietest ground in the world.
		 *
		 * <p>Every cell in the window contributes instead, weighted by how much
		 * further it is than the nearest. The weight reaches zero one blend width
		 * past the nearest cell, so a cell entering or leaving the window contributes
		 * nothing at the moment it does, and the result is continuous everywhere,
		 * including at the triple points where a nearest-seam blend would still have
		 * stepped.
		 */
		private double blendedBase(final PlateMap plates, final double blendWidth) {
			double nearest = Double.MAX_VALUE;

			for (int i = 0; i < scanCount; i++) {
				nearest = Math.min(nearest, distSq[i]);
			}

			nearest = Math.sqrt(nearest);

			double total = 0.0;
			double weightSum = 0.0;

			for (int i = 0; i < scanCount; i++) {
				double weight = 1.0 - smoothstep((Math.sqrt(distSq[i]) - nearest) / blendWidth);

				if (weight <= 0.0) {
					continue;
				}

				total += weight * crustAt(plates, i).baseElevation();
				weightSum += weight;
			}

			return weightSum <= 0.0 ? 0.0 : total / weightSum;
		}

		private static double smoothstep(final double x) {
			double t = Math.max(0.0, Math.min(1.0, x));

			return t * t * (3.0 - 2.0 * t);
		}
	}

	/** A crust cell across a plate boundary, and the frame of that boundary. */
	private record Neighbour(
			long cellX, long cellZ,
			double siteX, double siteZ,
			Plate plate,
			double across,
			double alongBoundary) {
	}

	/**
	 * Finds the nearest crust cell belonging to a different plate, and the boundary
	 * frame between it and the query's own cell.
	 *
	 * <p>Returns null when every cell in range belongs to the same plate, which is
	 * the common case: most of the world is plate interior.
	 *
	 * <p>Searches the same neighbourhood the crust Voronoi does. At 6,000-block cells
	 * that reaches 15,000 blocks, comfortably past the widest range the settings
	 * allow, so a boundary close enough to build anything is always found.
	 *
	 * <p>This is the expensive part of a plate lookup, because each candidate needs
	 * its plate resolved and that is a weighted nuclei search. Candidates are tested
	 * nearest-first and the loop stops at the first differing plate, so interiors pay
	 * the full price and margins, where the answer is usually one of the closest few,
	 * pay much less.
	 */
	private Neighbour nearestDifferentPlate(
			final double queryX, final double queryZ,
			final VoronoiSample own, final Plate ownPlate) {
		long centreX = voronoi.sites().cellX(queryX);
		long centreZ = voronoi.sites().cellZ(queryZ);
		int radius = voronoi.searchRadiusCells();

		long bestCellX = 0;
		long bestCellZ = 0;
		Plate bestPlate = null;
		double bestDistanceSq = Double.MAX_VALUE;

		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				long cellX = centreX + dx;
				long cellZ = centreZ + dz;

				if (cellX == own.cellX() && cellZ == own.cellZ()) {
					continue;
				}

				double siteX = voronoi.sites().pointX(cellX, cellZ);
				double siteZ = voronoi.sites().pointZ(cellX, cellZ);

				double offsetX = siteX - queryX;
				double offsetZ = siteZ - queryZ;
				double distanceSq = offsetX * offsetX + offsetZ * offsetZ;

				if (distanceSq >= bestDistanceSq) {
					continue;
				}

				Plate candidate = plateOf(cellX, cellZ);

				if (candidate.cellX() == ownPlate.cellX() && candidate.cellZ() == ownPlate.cellZ()) {
					continue;
				}

				bestDistanceSq = distanceSq;
				bestCellX = cellX;
				bestCellZ = cellZ;
				bestPlate = candidate;
			}
		}

		if (bestPlate == null) {
			return null;
		}

		return frameAgainst(queryX, queryZ, own, bestCellX, bestCellZ, bestPlate);
	}
}
