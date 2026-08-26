package com.fury.terramax.core.fluvial;

import com.fury.terramax.core.climate.Moisture;
import com.fury.terramax.core.climate.MoistureField;
import com.fury.terramax.core.terrain.HeightField;
import java.util.Arrays;

/**
 * Tier 2. One basin's channel network, routed on the real uplift surface.
 *
 * <p>This is where the physics lives. Tier 1 only decides which basin a point belongs
 * to; everything that makes drainage look like drainage happens here, at 1,000 blocks,
 * bounded to one basin and keyed by its outlet so every query inside it gets the same
 * answer.
 *
 * <p><b>The extent is padded and snapped to a global grid, and neither is cosmetic.</b>
 * Padding keeps the lattice edge, which the flood treats as open, away from the basin
 * proper, so a depression near the basin margin fills to its real spill point rather
 * than draining out of the side of the array. Snapping means two overlapping solves
 * sample the same world positions, so where they overlap they see identical ground.
 *
 * <p>The basin is deliberately <b>not</b> masked to tier 1's idea of its shape. Tier 1
 * decided that at 8,000 blocks and this lattice can see eight times finer, so masking
 * would force water across divides that tier 1 got slightly wrong. Instead the whole
 * padded box is routed on its own merits, which means a solve contains fragments of
 * neighbouring basins near its edges. That is harmless: a column asks for the nearest
 * channel, and a channel belonging to the basin next door is still a real channel in
 * the right place.
 *
 * <p>Where two neighbouring solves disagree, they disagree along the divide between
 * them, and a divide is by definition where channels are furthest away. The carve
 * takes the full uplift budget there regardless of which basin answered, so the
 * discontinuity lands exactly where its effect is smallest.
 */
public final class BasinNetwork {
	/** Passes of position smoothing applied to channel paths. */
	private static final int SMOOTHING_PASSES = 3;

	/**
	 * Reference vapour deficit for channel loss.
	 *
	 * <p>Air at the 15 degree reference temperature and zero humidity has a deficit of
	 * 1.0, so this is the length over which such air would take all but 1/e of a river
	 * passing through it. Real air is damper, so real losses are gentler and scale
	 * with the deficit actually present.
	 */
	private static final double CHANNEL_LOSS_LENGTH_BLOCKS = 400_000.0;

	/** Hard ceiling on lattice side, so a pathological basin cannot allocate freely. */
	private static final int MAX_LATTICE_CELLS = 420;

	/**
	 * Bisection steps when solving a lake level.
	 *
	 * <p>Twenty-four halvings resolve a 1,000-block depression to well under a block,
	 * which is finer than the terrain can express, so the answer is exact for every
	 * purpose the world has.
	 */
	private static final int BISECTION_STEPS = 24;

	/** Water this shallow is not a lake. Below it a closed basin reads as a dry floor. */
	private static final double PLAYA_DEPTH_BLOCKS = 1.0;

	private final long outletKey;
	private final DrainageSettings settings;
	private final FlowLattice flow;

	private final double originX;
	private final double originZ;
	private final double bucketSize;
	private final int bucketsX;
	private final int bucketsZ;

	private double[] segX0;
	private double[] segZ0;
	private double[] segX1;
	private double[] segZ1;
	private double[] segE0;
	private double[] segE1;
	private double[] segDischarge;
	private int[] segOrder;
	private int[] segStream;
	private int segments;

	private int[][] buckets;

	private final boolean clamped;
	private double threshold;
	private int monotonicViolations;
	private double worstRise;

	private double totalGain;
	private double meanRain;
	private double meanDeficit;
	private double meanRetention;

	private double[] gain;
	private double[] retention;
	private double[] cellDeficit;
	private double[] cellRain;

	private double[] lakeLevel;
	private boolean[] terminalLake;
	private boolean[] playa;
	private boolean[] terminalSink;
	private int[] depressionId;
	private boolean[] endorheicCell;
	private int endorheicCells;
	private int lakeCells;
	private int playaCells;
	private int terminalLakes;
	private int closedDepressions;
	private int terminalSinks;
	private int landCells = -1;

	public BasinNetwork(
			final long outletKey, final HeightField uplift, final MoistureField moisture,
			final BasinIndex basins, final DrainageSettings settings) {
		this.outletKey = outletKey;
		this.settings = settings;

		double spacing = settings.basinLatticeBlocks();
		double[] bounds = basins.boundsOf(outletKey);

		double span = Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]);
		double pad = Math.max(8.0 * spacing, span * 0.1);

		// Snapped to a global multiple of the lattice spacing, so two overlapping
		// solves sample identical world positions rather than interleaved ones.
		double minX = Math.floor((bounds[0] - pad) / spacing) * spacing;
		double minZ = Math.floor((bounds[1] - pad) / spacing) * spacing;
		double maxX = Math.ceil((bounds[2] + pad) / spacing) * spacing;
		double maxZ = Math.ceil((bounds[3] + pad) / spacing) * spacing;

		int wantX = (int) Math.round((maxX - minX) / spacing);
		int wantZ = (int) Math.round((maxZ - minZ) / spacing);
		int cellsX = Math.max(4, Math.min(MAX_LATTICE_CELLS, wantX));
		int cellsZ = Math.max(4, Math.min(MAX_LATTICE_CELLS, wantZ));

		this.clamped = cellsX < wantX || cellsZ < wantZ;
		this.originX = minX;
		this.originZ = minZ;

		this.flow = new FlowLattice(cellsX, cellsZ, spacing, minX, minZ);
		flow.sampleSurface(uplift);
		flow.floodFill(settings.baseLevelY());
		flow.route();

		accumulateDischarge(moisture);

		// Lakes are resolved between the two accumulation passes, and both passes are
		// needed. The first has to assume every depression spills, because until the
		// water balance is solved there is no way to know which do. The second runs
		// with the terminal lakes in place as sinks, so a river that ends in a closed
		// basin stops there instead of carrying phantom water to the sea.
		resolveLakes();
		markEndorheic();
		reaccumulateBelowLakes();

		this.threshold = calibrateThreshold();

		this.bucketSize = settings.bucketSizeBlocks();
		this.bucketsX = Math.max(1, (int) Math.ceil(cellsX * spacing / bucketSize));
		this.bucketsZ = Math.max(1, (int) Math.ceil(cellsZ * spacing / bucketSize));

		buildChannels();
		compact();
	}

	/**
	 * Releases everything only construction needed.
	 *
	 * <p>A query wants the channel segments, their index, and the lake and endorheic
	 * flags. It never wants the per-cell climate terms, the depression labelling or the
	 * flood tree, and those are the bulk of a basin's memory. Dropping them is what lets
	 * enough basins stay resident for a continental render to stop re-solving the same
	 * ones over and over.
	 */
	private void compact() {
		gain = null;
		retention = null;
		cellDeficit = null;
		cellRain = null;
		depressionId = null;
		terminalSink = null;
		flow.compact();
	}

	public long outletKey() {
		return outletKey;
	}

	public FlowLattice lattice() {
		return flow;
	}

	public int segmentCount() {
		return segments;
	}

	public boolean clamped() {
		return clamped;
	}

	public double threshold() {
		return threshold;
	}

	public int monotonicViolations() {
		return monotonicViolations;
	}

	public double worstRise() {
		return worstRise;
	}

	/** Total runoff produced across the lattice, before anything routes it. */
	public double totalGain() {
		return totalGain;
	}

	public double meanRain() {
		return meanRain;
	}

	public double meanDeficit() {
		return meanDeficit;
	}

	public double meanRetention() {
		return meanRetention;
	}

	/** Share of rainfall that becomes runoff here. Earth's land average is about 0.35. */
	public double runoffRatio() {
		return meanRain <= 0.0 ? 0.0 : (totalGain / Math.max(1, landCells())) / meanRain;
	}

	public int landCells() {
		if (landCells < 0) {
			int count = 0;

			for (int i = 0; i < flow.size(); i++) {
				if (flow.surface(i) > settings.baseLevelY()) {
					count++;
				}
			}

			landCells = count;
		}

		return landCells;
	}

	/**
	 * Mean spacing between neighbouring channels, in blocks.
	 *
	 * <p>Area over total channel length, which is the standard definition of drainage
	 * density inverted. Sampling distances to the nearest channel and multiplying by
	 * four, which is the answer for a set of parallel lines, is badly wrong for a
	 * dendritic network: channels cluster in valleys and leave interfluves empty, so
	 * that estimate reported 13,600 blocks where the real figure was 7,000.
	 */
	public double channelSpacing() {
		double length = 0.0;

		for (int s = 0; s < segments; s++) {
			length += Math.hypot(segX1[s] - segX0[s], segZ1[s] - segZ0[s]);
		}

		if (length <= 0.0) {
			return Double.MAX_VALUE;
		}

		double area = landCells() * flow.spacing() * flow.spacing();

		return area / length;
	}

	/** True when every channel descends from source to mouth, which is the invariant. */
	public boolean monotonic() {
		return monotonicViolations == 0;
	}

	/**
	 * Runoff per cell, and loss along the channel, from the moisture field.
	 *
	 * <p><b>Turc-Pike rather than a plain subtraction.</b> Actual evaporation is bounded
	 * by two different things and which one binds depends on the climate: in a desert
	 * there is more energy than water, so nearly all the rain evaporates and runoff
	 * tends to zero; in a rainforest there is more water than energy, so runoff is the
	 * excess. {@code E = P / sqrt(1 + (P/PET)^2)} moves between those two regimes
	 * smoothly and never produces negative runoff.
	 *
	 * <p><b>The potential-evaporation scale was calibrated, not chosen.</b> The vapour
	 * deficit runs 58 times larger than precipitation in this world's units, so a
	 * factor of 1 would have zeroed every river everywhere. 0.02 puts runoff at 36
	 * percent of rainfall against Earth's 35, with 87 percent of land producing any.
	 */
	private void accumulateDischarge(final MoistureField moisture) {
		int size = flow.size();
		gain = new double[size];
		retention = new double[size];
		cellDeficit = new double[size];
		cellRain = new double[size];
		double spacing = flow.spacing();

		double rainSum = 0.0;
		double deficitSum = 0.0;
		double retentionSum = 0.0;
		int land = 0;

		for (int i = 0; i < size; i++) {
			double worldX = flow.worldX(flow.cellX(i));
			double worldZ = flow.worldZ(flow.cellZ(i));

			Moisture air = moisture.at(worldX, worldZ);
			double deficit = Math.max(0.0, air.saturation() * (1.0 - air.humidity()));
			double potential = settings.evaporationFactor() * deficit;
			double rain = Math.max(0.0, air.precipitation());

			double evaporated = potential <= 0.0
					? 0.0
					: rain / Math.sqrt(1.0 + (rain / potential) * (rain / potential));

			gain[i] = Math.max(0.0, rain - evaporated);
			retention[i] = Math.exp(-spacing * deficit / CHANNEL_LOSS_LENGTH_BLOCKS);
			cellDeficit[i] = deficit;
			cellRain[i] = rain;

			if (flow.surface(i) > settings.baseLevelY()) {
				land++;
				rainSum += rain;
				deficitSum += deficit;
				retentionSum += retention[i];
				totalGain += gain[i];
			}
		}

		meanRain = land == 0 ? 0.0 : rainSum / land;
		meanDeficit = land == 0 ? 0.0 : deficitSum / land;
		meanRetention = land == 0 ? 1.0 : retentionSum / land;

		flow.accumulate(i -> gain[i], i -> retention[i]);
	}

	/**
	 * Re-runs accumulation with terminal lakes acting as sinks.
	 *
	 * <p>A lake that never reaches its spill point does not pass water on, so anything
	 * downstream of it is fed only by its own catchment. Skipping this pass would leave
	 * a full-sized river flowing out of a closed basin, which is the exact thing an
	 * endorheic basin is defined by not doing.
	 */
	private void reaccumulateBelowLakes() {
		if (terminalLakes == 0) {
			return;
		}

		flow.accumulate(
				i -> terminalSink[i] ? 0.0 : gain[i],
				i -> terminalSink[i] ? 0.0 : retention[i]);
	}

	/**
	 * Decides what standing water each depression holds.
	 *
	 * <p><b>Climate decides whether a basin spills, not topography alone.</b> Filling
	 * every depression to its rim would put brimming lakes in deserts wherever the
	 * ground happens to dip, and would make endorheic basins rare and accidental. On
	 * Earth they are common precisely because arid interiors are arid: the Caspian is
	 * closed because more water leaves its surface than arrives, not because its rim
	 * is unusually high.
	 *
	 * <p>Depressions come out of the flood already grouped: priority-flood raises every
	 * cell to the level of the lowest rim it sits behind, so one connected run of cells
	 * sharing a filled elevation is one depression, and that elevation is its spill
	 * point. Nothing has to be searched for.
	 */
	private void resolveLakes() {
		int size = flow.size();
		lakeLevel = new double[size];
		terminalLake = new boolean[size];
		playa = new boolean[size];
		terminalSink = new boolean[size];
		depressionId = new int[size];
		Arrays.fill(lakeLevel, DrainageSample.NO_LAKE);
		Arrays.fill(depressionId, -1);

		boolean[] visited = new boolean[size];
		int[] stack = new int[size];
		int[] group = new int[size];
		int nextId = 0;

		for (int start = 0; start < size; start++) {
			if (visited[start] || !flow.submerged(start)) {
				continue;
			}

			double spill = flow.filled(start);
			int id = nextId++;
			int count = 0;
			int top = 0;
			stack[top++] = start;
			visited[start] = true;
			depressionId[start] = id;

			while (top > 0) {
				int i = stack[--top];
				group[count++] = i;

				int ix = flow.cellX(i);
				int iz = flow.cellZ(i);

				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = ix + dx;
						int nz = iz + dz;

						if ((dx == 0 && dz == 0) || nx < 0 || nz < 0
								|| nx >= flow.width() || nz >= flow.depth()) {
							continue;
						}

						int j = flow.index(nx, nz);

						if (!visited[j] && flow.submerged(j) && flow.filled(j) == spill) {
							visited[j] = true;
							depressionId[j] = id;
							stack[top++] = j;
						}
					}
				}
			}

			solveLake(group, count, spill);
		}
	}

	/**
	 * Where one depression's water surface settles, by balancing inflow against
	 * evaporation from the surface that inflow would create.
	 *
	 * <p>Area grows with level, so loss grows with level while inflow does not. There is
	 * therefore exactly one crossing and bisection finds it. Three outcomes fall out of
	 * where that crossing lies: at or above the rim the lake overflows and the basin is
	 * exorheic, between rim and floor it is a terminal lake, and at or below the floor
	 * there is not enough water to stand at all and what is left is a dry playa, which
	 * is where salt collects.
	 *
	 * <p>A lake evaporates at the <b>potential</b> rate, not the Turc-Pike rate the land
	 * around it uses. That difference is the point: open water has an unlimited supply
	 * to evaporate from, which is why a lake in a desert loses so much more than the
	 * desert beside it.
	 */
	private void solveLake(final int[] group, final int count, final double spill) {
		double[] surfaces = new double[count];
		double inflow = 0.0;
		double deficitSum = 0.0;
		double rainSum = 0.0;

		for (int k = 0; k < count; k++) {
			int i = group[k];
			surfaces[k] = flow.surface(i);
			inflow = Math.max(inflow, flow.accumulated(i));
			deficitSum += cellDeficit[i];
			rainSum += cellRain[i];
		}

		Arrays.sort(surfaces);

		// A sill this shallow does not survive, and neither does the hollow behind it.
		// A river ponding behind a few blocks of rock incises through the sill, and the
		// same incision drains what was ponded, so the depression ends up as ordinary
		// valley floor rather than as a lake.
		//
		// Both halves of that matter. Skipping the check entirely made every sampling
		// artifact in the lattice a closed basin and put 78 percent of an arid basin
		// beyond reach of the sea. Filling these to the rim instead put 19 percent of
		// land under water, against about 2 percent on Earth. Draining them is what is
		// actually happening on the ground.
		if (spill - surfaces[0] < settings.closedBasinMinDepthBlocks()) {
			return;
		}

		double potential = settings.evaporationFactor() * (deficitSum / count);
		double lossPerCell = potential - rainSum / count;

		// Damper than it is thirsty: the lake gains more from rain than it loses to the
		// air, so it can only rise, and it fills to the rim whatever its catchment does.
		if (lossPerCell <= 0.0) {
			applyLake(group, count, spill, false, false);
			return;
		}

		if (lossPerCell * count <= inflow) {
			applyLake(group, count, spill, false, false);
			return;
		}

		double low = surfaces[0];
		double high = spill;

		for (int step = 0; step < BISECTION_STEPS; step++) {
			double mid = (low + high) * 0.5;

			if (lossPerCell * areaBelow(surfaces, mid) < inflow) {
				low = mid;
			} else {
				high = mid;
			}
		}

		double level = (low + high) * 0.5;
		boolean dry = level <= surfaces[0] + PLAYA_DEPTH_BLOCKS;

		applyLake(group, count, dry ? surfaces[0] : level, true, dry);
	}

	/** Cells whose ground sits below a candidate water level. */
	private static int areaBelow(final double[] sortedSurfaces, final double level) {
		int low = 0;
		int high = sortedSurfaces.length;

		while (low < high) {
			int mid = (low + high) >>> 1;

			if (sortedSurfaces[mid] < level) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}

		return low;
	}

	/**
	 * Writes one solved depression into the per-cell fields.
	 *
	 * <p><b>A dry basin's playa is its floor, not its footprint.</b> The group covers
	 * everything that would be underwater if the depression filled to its rim, which for
	 * a basin that never fills is mostly dry hillside above the pan. Marking all of it
	 * put playas on 21 percent of land in an arid basin, against a fraction of a percent
	 * on Earth. Only ground within a shallow depth of the floor is a salt pan.
	 */
	private void applyLake(
			final int[] group, final int count, final double level,
			final boolean terminal, final boolean dry) {
		// Counted only where water actually stands. A depression that came out dry is a
		// playa, not a lake, and calling it one made 795 of them out of a single basin.
		if (terminal) {
			closedDepressions++;

			if (!dry) {
				terminalLakes++;
			}
		}

		for (int k = 0; k < count; k++) {
			int i = group[k];

			if (dry) {
				if (flow.surface(i) <= level + PLAYA_DEPTH_BLOCKS) {
					playa[i] = true;
					playaCells++;
				}
			} else if (flow.surface(i) < level) {
				lakeLevel[i] = level;
				terminalLake[i] = terminal;
				lakeCells++;
			}

			// A closed basin passes nothing on, so flow is stopped at whichever of its
			// cells drains out of the group.
			//
			// Membership decides that, not elevation. Every cell in a filled depression
			// shares one filled value, and so does the rim it would spill over, which is
			// not in the group because it is not submerged. Testing for a lower
			// downstream neighbour therefore never fires: it found 0 sink cells across
			// 795 closed basins.
			if (terminal) {
				int next = flow.downstream(i);

				if (next < 0 || depressionId[next] != depressionId[i]) {
					terminalSink[i] = true;
					terminalSinks++;
				}
			}
		}
	}

	/**
	 * Standing water level at a point, or {@link DrainageSample#NO_LAKE}.
	 *
	 * <p>Takes the highest level among the four surrounding cells rather than the one
	 * containing the point, which extends a lake's influence one cell past its edge.
	 * That matters: the shoreline is drawn where the finished ground crosses this level,
	 * not where the lattice says the lake ends, so the level has to be available
	 * slightly outside the lake for the crossing to be found. A shoreline that followed
	 * the lattice would be a staircase with 1,000-block steps.
	 */
	public double lakeSurfaceAt(final double worldX, final double worldZ) {
		if (lakeCells == 0) {
			return DrainageSample.NO_LAKE;
		}

		int ix = (int) Math.floor((worldX - originX) / flow.spacing());
		int iz = (int) Math.floor((worldZ - originZ) / flow.spacing());
		double best = DrainageSample.NO_LAKE;

		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				int cx = ix + dx;
				int cz = iz + dz;

				if (cx < 0 || cz < 0 || cx >= flow.width() || cz >= flow.depth()) {
					continue;
				}

				best = Math.max(best, lakeLevel[flow.index(cx, cz)]);
			}
		}

		return best;
	}

	/** True where this point sits on a dried-out closed basin floor. */
	public boolean playaAt(final double worldX, final double worldZ) {
		if (playaCells == 0) {
			return false;
		}

		int ix = (int) Math.floor((worldX - originX) / flow.spacing());
		int iz = (int) Math.floor((worldZ - originZ) / flow.spacing());

		if (ix < 0 || iz < 0 || ix >= flow.width() || iz >= flow.depth()) {
			return false;
		}

		return playa[flow.index(ix, iz)];
	}

	/** True where this point is under a lake that never reaches its spill point. */
	public boolean terminalLakeAt(final double worldX, final double worldZ) {
		int ix = (int) Math.floor((worldX - originX) / flow.spacing());
		int iz = (int) Math.floor((worldZ - originZ) / flow.spacing());

		if (ix < 0 || iz < 0 || ix >= flow.width() || iz >= flow.depth()) {
			return false;
		}

		return terminalLake[flow.index(ix, iz)];
	}

	/**
	 * Marks every cell whose water never reaches the sea.
	 *
	 * <p><b>Endorheism is a climate outcome here, not a topological one, and it has to
	 * be.</b> Priority-flood removes every internal sink by construction, so after it
	 * runs there is no such thing as ground with no way out: asking whether a basin's
	 * outlet lies below sea level returns yes for every basin in the world. Defining it
	 * that way reported zero endorheic land on a continent full of dry closed basins.
	 *
	 * <p>What actually closes a basin is a lake that never fills to its rim. Water
	 * arrives, evaporates, and the chain to the sea is broken at that point even though
	 * the ground beyond it still slopes downhill. Everything upstream of such a lake is
	 * endorheic, which is why the Caspian's catchment is and the Volga's headwaters are.
	 *
	 * <p>Resolved in forward pop order, which visits a cell after the cell it drains
	 * into, so the answer downstream is always already known.
	 */
	private void markEndorheic() {
		int size = flow.size();
		endorheicCell = new boolean[size];

		// Guarded on closed depressions, not on lakes. A dry playa is the driest kind
		// of closed basin, not a basin that failed to be one: water still arrives there
		// and still never leaves. Checking for lakes alone reported 0.1 percent of land
		// as endorheic on a continent whose closed basins are mostly dry.
		if (closedDepressions == 0) {
			return;
		}

		int[] processOrder = flow.processOrder();

		for (int rank = 0; rank < size; rank++) {
			int i = processOrder[rank];
			int next = flow.downstream(i);

			// A cell is endorheic if it is where the chain breaks, or if the cell it
			// drains into is. Everything upstream of a closed basin is closed with it,
			// which is why the Volga's headwaters are endorheic and not just the
			// Caspian's shore. A cell leaving through the lattice edge belongs to a
			// neighbouring basin and counts as reaching the sea rather than being
			// guessed at.
			endorheicCell[i] = terminalSink[i] || (next >= 0 && endorheicCell[next]);

			if (endorheicCell[i] && flow.surface(i) > settings.baseLevelY()) {
				endorheicCells++;
			}
		}
	}

	/** Closed depressions found, whether they hold water or dried out. */
	public int closedDepressionCount() {
		return closedDepressions;
	}

	/**
	 * Cells where a closed basin breaks the chain to the sea.
	 *
	 * <p>Counted while marking rather than by scanning afterwards, because the marks
	 * themselves are released once the network is built. A diagnostic that reads freed
	 * state is worse than no diagnostic: it throws in the one place someone is trying to
	 * find out what went wrong.
	 */
	public int terminalSinkCount() {
		return terminalSinks;
	}

	public int endorheicCellCount() {
		return endorheicCells;
	}

	/** True where water at this point never reaches the sea. */
	public boolean endorheicAt(final double worldX, final double worldZ) {
		if (endorheicCells == 0) {
			return false;
		}

		int ix = (int) Math.floor((worldX - originX) / flow.spacing());
		int iz = (int) Math.floor((worldZ - originZ) / flow.spacing());

		if (ix < 0 || iz < 0 || ix >= flow.width() || iz >= flow.depth()) {
			return false;
		}

		return endorheicCell[flow.index(ix, iz)];
	}

	/** Share of this lattice's land that drains to no sea. */
	public double endorheicShare() {
		int land = landCells();

		return land == 0 ? 0.0 : (double) endorheicCells / land;
	}

	public int terminalLakeCount() {
		return terminalLakes;
	}

	public double lakeAreaShare() {
		int land = landCells();

		return land == 0 ? 0.0 : (double) lakeCells / land;
	}

	public double playaAreaShare() {
		int land = landCells();

		return land == 0 ? 0.0 : (double) playaCells / land;
	}

	/**
	 * The discharge above which a cell counts as a channel.
	 *
	 * <p><b>A quantile of this basin's own distribution, never a constant.</b>
	 * Accumulated discharge is the most skewed quantity in this generator: almost every
	 * cell carries nearly none and a handful of trunk cells carry orders of magnitude
	 * more. Choosing a fixed number and hoping it lands at the right channel density is
	 * exactly the mistake {@code Equaliser} was written to prevent, and this project
	 * has already made it twice, at {@code PlateMap.calibrateContinentThreshold} and
	 * again at region type selection.
	 *
	 * <p>Per basin rather than globally, which is also more correct than it looks. A
	 * wet mountain basin and a dry interior basin have genuinely different discharge
	 * distributions, and one shared threshold would give the dry one almost no channels
	 * at all, which is not what a dry landscape looks like: it has valleys, they are
	 * just rarely running.
	 */
	private double calibrateThreshold() {
		double[] sorted = new double[flow.size()];
		int count = 0;

		for (int i = 0; i < flow.size(); i++) {
			if (flow.surface(i) > settings.baseLevelY()) {
				sorted[count++] = flow.accumulated(i);
			}
		}

		if (count == 0) {
			return Double.MAX_VALUE;
		}

		sorted = Arrays.copyOf(sorted, count);
		Arrays.sort(sorted);

		// A channel every channelSpacingTargetBlocks means roughly this share of cells
		// sit on one, since a channel occupies about one cell width.
		double share = Math.min(0.5, flow.spacing() / settings.channelSpacingTargetBlocks());
		int cut = (int) Math.round((1.0 - share) * (count - 1));

		return sorted[Math.max(0, Math.min(count - 1, cut))];
	}

	private void buildChannels() {
		int size = flow.size();
		boolean[] channel = new boolean[size];
		int channelCells = 0;

		for (int i = 0; i < size; i++) {
			if (flow.accumulated(i) >= threshold && flow.surface(i) > settings.baseLevelY()) {
				channel[i] = true;
				channelCells++;
			}
		}

		int[] order = strahler(channel);
		int[] stream = streams(channel, order);
		double[] pointX = new double[size];
		double[] pointZ = new double[size];

		for (int i = 0; i < size; i++) {
			pointX[i] = flow.worldX(flow.cellX(i));
			pointZ[i] = flow.worldZ(flow.cellZ(i));
		}

		smoothPaths(channel, pointX, pointZ);
		emitSegments(channel, order, stream, pointX, pointZ, channelCells);
		indexSegments();
		checkMonotonic(channel);
	}

	/**
	 * Strahler order, by the standard rule: two equal branches make the next order up,
	 * an unequal join keeps the larger.
	 *
	 * <p>Computed in reverse pop order, which visits every cell before the cell it
	 * drains into, so a junction always already knows what arrived at it.
	 */
	private int[] strahler(final boolean[] channel) {
		int size = flow.size();
		int[] order = new int[size];
		int[] childMax = new int[size];
		int[] childMaxCount = new int[size];
		int[] processOrder = flow.processOrder();

		for (int rank = size - 1; rank >= 0; rank--) {
			int i = processOrder[rank];

			if (!channel[i]) {
				continue;
			}

			int own = childMax[i] == 0
					? 1
					: (childMaxCount[i] >= 2 ? childMax[i] + 1 : childMax[i]);
			order[i] = own;

			int next = flow.downstream(i);

			if (next < 0 || !channel[next]) {
				continue;
			}

			if (own > childMax[next]) {
				childMax[next] = own;
				childMaxCount[next] = 1;
			} else if (own == childMax[next]) {
				childMaxCount[next]++;
			}
		}

		return order;
	}

	/**
	 * Labels each channel cell with the stream it belongs to.
	 *
	 * <p><b>Segments are not streams, and conflating them broke the divide.</b> Standing
	 * beside a river, the two nearest <i>segments</i> are two consecutive pieces of that
	 * same river and are almost exactly equidistant, so a divide defined on segments
	 * reports itself as being everywhere. A divide is the boundary between two different
	 * streams, so the search has to be able to tell them apart.
	 *
	 * <p>A stream is a maximal run of channel cells of the same Strahler order. It ends
	 * where two equal branches meet and the order steps up, which is exactly where a
	 * real interfluve begins. So the spur between a river and its own tributary counts
	 * as a divide, which is right: that is a genuine ridge and the ground rises onto it.
	 *
	 * <p>Assigned in forward pop order, which visits a cell after the cell it drains
	 * into, so the downstream stream label is always already known.
	 */
	private int[] streams(final boolean[] channel, final int[] order) {
		int size = flow.size();
		int[] stream = new int[size];
		Arrays.fill(stream, -1);

		int[] processOrder = flow.processOrder();
		int next = 0;

		for (int rank = 0; rank < size; rank++) {
			int i = processOrder[rank];

			if (!channel[i]) {
				continue;
			}

			int down = flow.downstream(i);

			stream[i] = down >= 0 && channel[down] && order[down] == order[i] && stream[down] >= 0
					? stream[down]
					: next++;
		}

		return stream;
	}

	/**
	 * Pulls channel paths off the eight-way lattice.
	 *
	 * <p>D8 can only step in eight directions, so a river running at any other angle
	 * comes out as a staircase. Averaging each channel point toward its upstream and
	 * downstream neighbours removes the steps while moving the path less than half a
	 * cell, so the channel stays in the valley the routing put it in.
	 */
	private void smoothPaths(
			final boolean[] channel, final double[] pointX, final double[] pointZ) {
		int size = flow.size();
		int[] upstream = new int[size];
		Arrays.fill(upstream, -1);

		// One upstream neighbour is enough to smooth against, and the largest is the
		// one the channel visually continues from.
		double[] bestFlow = new double[size];

		for (int i = 0; i < size; i++) {
			int next = flow.downstream(i);

			if (!channel[i] || next < 0 || !channel[next]) {
				continue;
			}

			if (flow.accumulated(i) > bestFlow[next]) {
				bestFlow[next] = flow.accumulated(i);
				upstream[next] = i;
			}
		}

		double[] nextX = new double[size];
		double[] nextZ = new double[size];

		for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
			System.arraycopy(pointX, 0, nextX, 0, size);
			System.arraycopy(pointZ, 0, nextZ, 0, size);

			for (int i = 0; i < size; i++) {
				int down = flow.downstream(i);
				int up = upstream[i];

				if (!channel[i] || down < 0 || !channel[down] || up < 0) {
					continue;
				}

				nextX[i] = pointX[i] * 0.5 + (pointX[up] + pointX[down]) * 0.25;
				nextZ[i] = pointZ[i] * 0.5 + (pointZ[up] + pointZ[down]) * 0.25;
			}

			System.arraycopy(nextX, 0, pointX, 0, size);
			System.arraycopy(nextZ, 0, pointZ, 0, size);
		}
	}

	private void emitSegments(
			final boolean[] channel, final int[] order, final int[] stream,
			final double[] pointX, final double[] pointZ, final int channelCells) {
		segX0 = new double[channelCells];
		segZ0 = new double[channelCells];
		segX1 = new double[channelCells];
		segZ1 = new double[channelCells];
		segE0 = new double[channelCells];
		segE1 = new double[channelCells];
		segDischarge = new double[channelCells];
		segOrder = new int[channelCells];
		segStream = new int[channelCells];
		segments = 0;

		for (int i = 0; i < flow.size(); i++) {
			int next = flow.downstream(i);

			if (!channel[i] || next < 0 || !channel[next]) {
				continue;
			}

			segX0[segments] = pointX[i];
			segZ0[segments] = pointZ[i];
			segX1[segments] = pointX[next];
			segZ1[segments] = pointZ[next];

			// The bed, not the water surface.
			//
			// These two are the same everywhere except inside a depression, where the
			// fill raised the water above the ground. The carve interpolates the ground
			// from here up to the divide, so handing it the water surface fills the
			// valley with rock to lake level and turns every lake into a plateau. It
			// also made the inversion guard fire on a fifth of all samples, because the
			// water surface genuinely does stand above the uplift budget at those cells.
			//
			// Monotonicity is not lost by this. It was never a property of the bed: a
			// lake bed dips below its own outlet, which is what makes it a lake. The
			// invariant belongs to the water surface and is checked there.
			segE0[segments] = flow.surface(i);
			segE1[segments] = flow.surface(next);
			segDischarge[segments] = flow.accumulated(i);
			segOrder[segments] = order[i];
			segStream[segments] = stream[i];
			segments++;
		}
	}

	private void indexSegments() {
		int[] counts = new int[bucketsX * bucketsZ];

		for (int s = 0; s < segments; s++) {
			forEachBucket(s, bucket -> counts[bucket]++);
		}

		buckets = new int[counts.length][];

		for (int b = 0; b < counts.length; b++) {
			buckets[b] = new int[counts[b]];
		}

		int[] filled = new int[counts.length];

		for (int s = 0; s < segments; s++) {
			int segment = s;
			forEachBucket(s, bucket -> buckets[bucket][filled[bucket]++] = segment);
		}
	}

	/** Visits every bucket a segment touches, by walking its bounding box. */
	private void forEachBucket(final int segment, final java.util.function.IntConsumer visit) {
		int minBX = bucketIndex(Math.min(segX0[segment], segX1[segment]) - originX, bucketsX);
		int maxBX = bucketIndex(Math.max(segX0[segment], segX1[segment]) - originX, bucketsX);
		int minBZ = bucketIndex(Math.min(segZ0[segment], segZ1[segment]) - originZ, bucketsZ);
		int maxBZ = bucketIndex(Math.max(segZ0[segment], segZ1[segment]) - originZ, bucketsZ);

		for (int bz = minBZ; bz <= maxBZ; bz++) {
			for (int bx = minBX; bx <= maxBX; bx++) {
				visit.accept(bz * bucketsX + bx);
			}
		}
	}

	private int bucketIndex(final double offset, final int limit) {
		return Math.max(0, Math.min(limit - 1, (int) Math.floor(offset / bucketSize)));
	}

	/**
	 * Walks every channel from source to mouth and confirms elevation never rises.
	 *
	 * <p><b>This is the invariant the entire fluvial design rests on.</b> It is cheap,
	 * and it catches exactly the class of defect a colour ramp hides completely: a
	 * channel that runs 300 blocks uphill looks identical on a map to one that does
	 * not. The fill is supposed to make this impossible, so a violation means the fill
	 * or the routing is wrong, not that the tolerance needs loosening.
	 */
	private void checkMonotonic(final boolean[] channel) {
		monotonicViolations = 0;
		worstRise = 0.0;

		// Checked on the filled surface, which is the water, not on the segment
		// elevations, which are now the bed. A bed is allowed to rise going downstream;
		// that is a lake. A water surface is not, and that is the invariant.
		for (int i = 0; i < flow.size(); i++) {
			int next = flow.downstream(i);

			if (!channel[i] || next < 0 || !channel[next]) {
				continue;
			}

			double rise = flow.filled(next) - flow.filled(i);

			if (rise > 0.0) {
				monotonicViolations++;
				worstRise = Math.max(worstRise, rise);
			}
		}
	}

	/** The two nearest channels to a point, written into a caller-supplied result. */
	public void nearestTwo(final double worldX, final double worldZ, final Nearest out) {
		out.reset();
		out.fallbackHalfSpacing(settings.channelSpacingTargetBlocks() * 0.5);
		offerNear(worldX, worldZ, out);
	}

	/**
	 * Offers this basin's channels into a result the caller already owns.
	 *
	 * <p>Separate from {@link #nearestTwo} so tier 3 can add its creeks to the same
	 * search rather than running its own and merging the answers.
	 */
	public void offerNear(final double worldX, final double worldZ, final Nearest out) {
		if (segments == 0) {
			return;
		}

		int bx = bucketIndex(worldX - originX, bucketsX);
		int bz = bucketIndex(worldZ - originZ, bucketsZ);

		search(worldX, worldZ, bx, bz, 1, out);

		// A basin interior with sparse channels can legitimately have nothing in the
		// immediate neighbourhood. Widen once rather than reporting no channel, which
		// the carve would read as a divide.
		if (out.distance1 == Double.MAX_VALUE) {
			search(worldX, worldZ, bx, bz, 4, out);
		}
	}

	private void search(
			final double worldX, final double worldZ,
			final int bx, final int bz, final int radius, final Nearest out) {
		for (int dz = -radius; dz <= radius; dz++) {
			int cz = bz + dz;

			if (cz < 0 || cz >= bucketsZ) {
				continue;
			}

			for (int dx = -radius; dx <= radius; dx++) {
				int cx = bx + dx;

				if (cx < 0 || cx >= bucketsX) {
					continue;
				}

				for (int s : buckets[cz * bucketsX + cx]) {
					consider(worldX, worldZ, s, out);
				}
			}
		}
	}

	private void consider(
			final double worldX, final double worldZ, final int s, final Nearest out) {
		double dx = segX1[s] - segX0[s];
		double dz = segZ1[s] - segZ0[s];
		double lengthSquared = dx * dx + dz * dz;

		double t = lengthSquared <= 0.0
				? 0.0
				: Math.max(0.0, Math.min(1.0,
						((worldX - segX0[s]) * dx + (worldZ - segZ0[s]) * dz) / lengthSquared));

		double nearestX = segX0[s] + dx * t;
		double nearestZ = segZ0[s] + dz * t;

		out.offer(
				Math.hypot(worldX - nearestX, worldZ - nearestZ),
				segStream[s],
				segE0[s] + (segE1[s] - segE0[s]) * t,
				segDischarge[s],
				segOrder[s]);
	}

	/** How many distinct streams this basin holds, so tier 3 can number past them. */
	public int streamCount() {
		int highest = -1;

		for (int s = 0; s < segments; s++) {
			highest = Math.max(highest, segStream[s]);
		}

		return highest + 1;
	}

	/**
	 * Visits every channel segment overlapping a rectangle.
	 *
	 * <p>Tier 3 hangs its creeks off these, and needs a margin beyond the patch it is
	 * building so a tributary reaching in from a trunk just outside still appears. The
	 * bucket grid makes that a local walk rather than a scan of the whole basin.
	 */
	public void forEachSegmentNear(
			final double minX, final double minZ, final double maxX, final double maxZ,
			final SegmentVisitor visitor) {
		int loX = bucketIndex(minX - originX, bucketsX);
		int hiX = bucketIndex(maxX - originX, bucketsX);
		int loZ = bucketIndex(minZ - originZ, bucketsZ);
		int hiZ = bucketIndex(maxZ - originZ, bucketsZ);

		boolean[] seen = new boolean[segments];

		for (int bz = loZ; bz <= hiZ; bz++) {
			for (int bx = loX; bx <= hiX; bx++) {
				for (int s : buckets[bz * bucketsX + bx]) {
					if (seen[s]) {
						continue;
					}

					seen[s] = true;
					visitor.accept(
							segX0[s], segZ0[s], segX1[s], segZ1[s],
							segE0[s], segE1[s], segDischarge[s], segOrder[s]);
				}
			}
		}
	}

	/** Receives one channel segment. */
	@FunctionalInterface
	public interface SegmentVisitor {
		void accept(double x0, double z0, double x1, double z1,
				double e0, double e1, double discharge, int order);
	}

	/**
	 * The result of a nearest-channel search, reused rather than allocated.
	 *
	 * <p>Two channels rather than one, because the second is what gives the divide.
	 * Where the two nearest channels are equidistant, that locus is exactly the
	 * drainage divide, since a divide is the Voronoi boundary of the channel network.
	 * No divide structure is built anywhere in this subsystem; it falls out of here.
	 *
	 * <p>Mutable and caller-owned by design. This runs for every column of every chunk,
	 * and allocating a result per column would be the dominant cost of the query.
	 */
	public static final class Nearest {
		public double distance1;
		public double distance2;
		public double elevation1;
		public double discharge1;
		public int order1;
		public int stream1;
		public int stream2;

		/** Half the spacing channels are targeted at, used when only one stream is near. */
		private double fallbackHalfSpacing = 3_000.0;

		public void reset() {
			distance1 = Double.MAX_VALUE;
			distance2 = Double.MAX_VALUE;
			elevation1 = 0.0;
			discharge1 = 0.0;
			order1 = 0;
			stream1 = -1;
			stream2 = -1;
		}

		public void fallbackHalfSpacing(final double blocks) {
			this.fallbackHalfSpacing = blocks;
		}

		/**
		 * Offers one candidate channel, keeping the nearest two <i>distinct streams</i>.
		 *
		 * <p>Shared by both tiers on purpose, so a trunk and a creek compete on equal
		 * terms and the divide falls out of whichever two streams are genuinely closest.
		 * Running them separately and merging afterwards would lose the case that
		 * matters most: standing between a river and its own tributary, where the two
		 * nearest streams come from different tiers.
		 *
		 * <p>Only demotes the current best into second place when the newcomer belongs
		 * to a different stream. Otherwise second place would fill with the next segment
		 * of the same river, which says nothing about where a divide is.
		 */
		public void offer(
				final double distance, final int stream,
				final double elevation, final double flow, final int channelOrder) {
			if (distance < distance1) {
				if (stream != stream1) {
					distance2 = distance1;
					stream2 = stream1;
				}

				distance1 = distance;
				stream1 = stream;
				elevation1 = elevation;
				discharge1 = flow;
				order1 = channelOrder;
			} else if (stream != stream1 && distance < distance2) {
				distance2 = distance;
				stream2 = stream;
			}
		}

		public boolean found() {
			return distance1 < Double.MAX_VALUE;
		}

		/**
		 * Hillslope position: 0 on a channel, 1 on the divide.
		 *
		 * <p>Falls straight out of the two distances, with no divide ever having been
		 * located. Where the nearest two channels are equally far, the point is on the
		 * boundary between their catchments, which is what a divide is.
		 */
		public double hillslope() {
			if (!found()) {
				return 1.0;
			}

			// Only one stream in reach, which happens on a coastal strip or a small
			// island where there genuinely is no second river to be halfway between.
			// Fall back to the targeted spacing rather than declaring the whole area a
			// divide, which would leave it uncarved.
			if (distance2 == Double.MAX_VALUE) {
				return Math.min(1.0, distance1 / fallbackHalfSpacing);
			}

			double total = distance1 + distance2;

			return total <= 0.0 ? 0.0 : Math.min(1.0, 2.0 * distance1 / total);
		}
	}
}
