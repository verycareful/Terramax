package com.fury.terramax.core.fluvial;

import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.core.util.Hashing;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 3. Synthetic tributaries below the resolution routing can usefully reach.
 *
 * <p><b>Why this is not routed, and why that is the more faithful choice.</b> Below
 * roughly 2,300 blocks the uplift surface carries no drainage information: region
 * relief is noise at that wavelength and there is nothing beneath it but detail noise.
 * Routing flow over noise produces channels that follow noise, which is not more
 * truthful than a synthetic network, only more expensive. A tree carrying the real
 * Hortonian statistics is closer to a real creek network than anything a router could
 * extract from a field that does not contain one.
 *
 * <p>That argument only holds if the statistics are actually carried, so they are:
 * bifurcation ratio near 4, length ratio near 2, Hack's law on length against area,
 * and the slope-area relation for gradient. The simulator measures them off the
 * generated trees rather than trusting that asking for them was enough, because
 * generating with a target and never checking the result is how the region weights came
 * to produce zero inselberg plains.
 *
 * <p><b>Measuring changed the answer.</b> Branches die where they meet the uplift
 * budget, and a branch spawned high on a hillside has less room left to climb than one
 * spawned near the trunk, so deeper levels die more often. Attempting four children per
 * branch produced a realised ratio of 1.88. Attempting eight produces 3.85, which is
 * Horton's number on the network that actually exists rather than on the one that was
 * requested.
 *
 * <p><b>Elevations are assigned upward, so monotonicity is free.</b> Each node sits at
 * its downstream neighbour's elevation plus a rise, so a creek can only climb going
 * upstream and can never run backwards. Tier 2 gets the same guarantee from the flood;
 * this gets it a different way, without consulting the terrain at all.
 *
 * <p>Termination is what keeps the synthetic half honest. A tributary stops when its
 * assigned elevation reaches the local uplift budget, which is to say when it runs out
 * of hillside to climb. Creeks therefore end at divides, because the divide is exactly
 * where the budget runs out, and the tree respects real topography at its edges even
 * though it never routed on any.
 *
 * <p>Patches are a pure function of their key, so any patch can be built in any order
 * on any thread, and all randomness goes through {@link Hashing} rather than any
 * stateful generator.
 */
public final class CreekTrees {
	private static final long SALT_SPAWN = 0x94D049BB133111EBL;
	private static final long SALT_ANGLE = 0xBF58476D1CE4E5B9L;
	private static final long SALT_SIDE = 0x9E3779B97F4A7C15L;
	private static final long SALT_LENGTH = 0xC2B2AE3D27D4EB4FL;
	private static final long SALT_STREAM = 0x2545F4914F6CDD1DL;

	/** Patch side, in blocks. Large enough to hold whole creek trees, small to cache. */
	private static final double PATCH_BLOCKS = 8_000.0;

	/** Straight-line steps a creek is drawn with, so it bends rather than being a spoke. */
	private static final int SEGMENTS_PER_BRANCH = 3;

	/**
	 * Marks a stream id as belonging to a creek rather than to a tier 2 channel.
	 *
	 * <p>Creek ids are hashed from position and trunk ids are small counters, so without
	 * a separating bit a creek could be handed the same id as a trunk and the divide
	 * between them would vanish.
	 */
	private static final int CREEK_STREAM_MARK = 0x4000_0000;

	/** How far a creek wanders per step, as a fraction of the step length. */
	private static final double WANDER = 0.22;

	private final long seed;
	private final HeightField uplift;
	private final DrainageSettings settings;
	private final Map<Long, Patch> patches = new ConcurrentHashMap<>();
	private final java.util.Queue<Long> order =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	public CreekTrees(final long seed, final HeightField uplift, final DrainageSettings settings) {
		this.seed = seed;
		this.uplift = uplift;
		this.settings = settings;
	}

	public static long patchKey(final double worldX, final double worldZ) {
		int px = (int) Math.floor(worldX / PATCH_BLOCKS);
		int pz = (int) Math.floor(worldZ / PATCH_BLOCKS);

		return BasinIndex.packCell(px, pz);
	}

	public int cachedPatches() {
		return patches.size();
	}

	/**
	 * The creek patch covering a point, built once and shared.
	 *
	 * <p>Get-then-put rather than {@code computeIfAbsent}, as {@code MoistureField}
	 * does. A patch is cheap enough that a duplicated build costs less than holding a
	 * bin lock across one would.
	 */
	public Patch patchAt(final double worldX, final double worldZ, final BasinNetwork basin) {
		long key = patchKey(worldX, worldZ);
		Patch cached = patches.get(key);

		if (cached != null) {
			return cached;
		}

		Patch built = build(key, basin);
		Patch existing = patches.putIfAbsent(key, built);

		if (existing == null) {
			order.add(key);

			// Bounded the same way basins are, and for the same reason: a planetary
			// render must not hold every patch it ever touched. Patches are small, so
			// the limit is large and eviction is rare in play.
			while (patches.size() > settings.creekCacheLimit()) {
				Long oldest = order.poll();

				if (oldest == null) {
					break;
				}

				patches.remove(oldest);
			}
		}

		return existing != null ? existing : built;
	}

	private Patch build(final long key, final BasinNetwork basin) {
		double originX = BasinIndex.unpackCellX(key) * PATCH_BLOCKS;
		double originZ = BasinIndex.unpackCellZ(key) * PATCH_BLOCKS;

		Builder builder = new Builder(originX, originZ);

		// A creek can reach into this patch from a trunk outside it, so anchors are
		// gathered from a margin as well. The margin has to cover the furthest a tree
		// can reach from its anchor, which is the first branch plus every shorter
		// generation after it, not a guessed multiple of the spacing: a creek whose
		// anchor falls outside the margin simply vanishes on this side of the boundary
		// while existing on the other.
		double reach = maximumReach();

		basin.forEachSegmentNear(
				originX - reach, originZ - reach,
				originX + PATCH_BLOCKS + reach, originZ + PATCH_BLOCKS + reach,
				builder::spawnAlong);

		return builder.finish();
	}

	/**
	 * Furthest a creek tree can extend from the trunk it hangs off.
	 *
	 * <p>The first branch is at most one creek spacing long and each generation is
	 * shorter by the length ratio, so the total is a geometric sum. Doubled, because a
	 * branch wanders as it grows and does not run straight away from its anchor.
	 */
	private double maximumReach() {
		double length = settings.creekSpacingBlocks();
		double total = 0.0;

		for (int level = 0; level < settings.creekLevels(); level++) {
			total += length;
			length /= settings.lengthRatio();
		}

		return total * 2.0;
	}

	/** Builds one patch's creeks, then freezes them into flat arrays. */
	private final class Builder {
		private final double originX;
		private final double originZ;

		private double[] x0 = new double[256];
		private double[] z0 = new double[256];
		private double[] x1 = new double[256];
		private double[] z1 = new double[256];
		private double[] e0 = new double[256];
		private double[] e1 = new double[256];
		private double[] discharge = new double[256];
		private int[] order = new int[256];
		private int[] stream = new int[256];
		private int count;

		private int branches;
		private int junctions;
		private final int[] perLevel = new int[16];
		private double lengthSum;
		private double areaSum;

		// Regression sums for Hack's law, accumulated as branches are made so the
		// exponent can be measured off the network rather than assumed from the
		// setting that asked for it.
		private double logLengthSum;
		private double logAreaSum;
		private double logAreaSquaredSum;
		private double logProductSum;

		Builder(final double originX, final double originZ) {
			this.originX = originX;
			this.originZ = originZ;
		}

		/**
		 * Hangs tributaries off one tier 2 segment.
		 *
		 * <p>Spawn positions come from the segment's own coordinates, so the same
		 * tributaries appear no matter which patch asks about this segment. A creek
		 * crossing a patch boundary is therefore the same creek on both sides.
		 */
		void spawnAlong(
				final double ax, final double az, final double bx, final double bz,
				final double ae, final double be, final double flow, final int trunkOrder) {
			double length = Math.hypot(bx - ax, bz - az);

			if (length <= 0.0 || trunkOrder <= 0) {
				return;
			}

			// One spawn per creek spacing along the trunk, on average. Sub-one values
			// are handled by hashing rather than rounding, or short segments would
			// never spawn anything at all.
			double expected = length / settings.creekSpacingBlocks();
			int slots = Math.max(1, (int) Math.ceil(expected));
			double chance = expected / slots;

			for (int slot = 0; slot < slots; slot++) {
				long hx = Math.round(ax + (bx - ax) * (slot + 0.5) / slots);
				long hz = Math.round(az + (bz - az) * (slot + 0.5) / slots);

				if (Hashing.unitDouble(seed, hx, hz, SALT_SPAWN) > chance) {
					continue;
				}

				double t = (slot + 0.5) / slots;
				double px = ax + (bx - ax) * t;
				double pz = az + (bz - az) * t;
				double pe = ae + (be - ae) * t;

				// Alternating sides with a hashed flip, which is what real networks do.
				// Always alternating is too regular; always random leaves long stretches
				// feeding from one bank.
				double sideRoll = Hashing.unitDouble(seed, hx, hz, SALT_SIDE);
				double side = ((slot & 1) == 0) == (sideRoll < 0.75) ? 1.0 : -1.0;

				double downX = (bx - ax) / length;
				double downZ = (bz - az) / length;

				double angle = Math.toRadians(settings.junctionAngleMinDegrees()
						+ (settings.junctionAngleMaxDegrees() - settings.junctionAngleMinDegrees())
								* Hashing.unitDouble(seed, hx, hz, SALT_ANGLE));

				// Facing upstream: a tributary joins pointing back the way the trunk
				// came, which is why river junctions form arrowheads pointing downstream.
				double baseAngle = Math.atan2(-downZ, -downX) + side * angle;

				double area = Math.max(1.0, flow);
				double branchLength = settings.creekSpacingBlocks()
						* (0.55 + 0.45 * Hashing.unitDouble(seed, hx, hz, SALT_LENGTH));

				grow(px, pz, pe, baseAngle, branchLength, area,
						Math.max(1, trunkOrder - 1), settings.creekLevels(),
						streamIdAt(px, pz));
			}
		}

		/**
		 * Grows one tributary and its children.
		 *
		 * <p>Elevation only ever rises going upstream, so the branch is monotonic by
		 * construction. The gradient comes from the slope-area relation, which is what
		 * makes headwaters steep and trunks flat out of one expression rather than two
		 * separately tuned ones.
		 */
		private void grow(
				final double fromX, final double fromZ, final double fromElevation,
				final double angle, final double length, final double area,
				final int branchOrder, final int levelsLeft, final int streamId) {
			if (levelsLeft <= 0 || length < settings.creekSpacingBlocks() * 0.15) {
				return;
			}

			double gradient = settings.gradientScale()
					* Math.pow(Math.max(1.0, area), -settings.slopeAreaExponent());

			double x = fromX;
			double z = fromZ;
			double elevation = fromElevation;
			double heading = angle;
			double step = length / SEGMENTS_PER_BRANCH;

			// Horton's bifurcation ratio is the count of streams of one order per
			// stream of the next order up, so it is the number of children directly,
			// not half of it. Halving it built strictly binary trees and measured 2.
			int children = Math.max(2, (int) Math.round(settings.bifurcationRatio()));
			int grown = 0;

			branches++;
			lengthSum += length;
			areaSum += area;
			junctions++;
			perLevel[Math.min(perLevel.length - 1, levelsLeft)]++;

			double logLength = Math.log(length);
			double logArea = Math.log(Math.max(1.0e-9, area));
			logLengthSum += logLength;
			logAreaSum += logArea;
			logAreaSquaredSum += logArea * logArea;
			logProductSum += logArea * logLength;

			// Hack's law inverted: a branch shorter by the length ratio drains an area
			// smaller by that ratio raised to 1/0.57, which is what keeps the measured
			// exponent where it was asked to be.
			double childArea = area
					/ Math.pow(settings.lengthRatio(), 1.0 / settings.hackExponent());

			for (int piece = 0; piece < SEGMENTS_PER_BRANCH; piece++) {
				long hx = Math.round(x);
				long hz = Math.round(z);

				heading += (Hashing.unitDouble(seed, hx, hz, SALT_ANGLE + piece) - 0.5)
						* WANDER * 2.0;

				double nx = x + Math.cos(heading) * step;
				double nz = z + Math.sin(heading) * step;
				double ne = elevation + step * gradient;

				// The divide. A creek climbs until it reaches the height the ground
				// would stand at with nothing eroded away, and there is no hillside
				// left above that. This is the only place tier 3 consults the terrain,
				// and it is what stops the trees being free-floating decoration.
				if (ne >= uplift.heightAt(nx, nz)) {
					return;
				}

				add(x, z, nx, nz, elevation, ne, area, branchOrder, streamId);

				// Children join along the branch, not all at its tip. Tributaries reach
				// a stream down its whole length; hanging them off the head instead
				// produces a spoke, which is not a shape rivers make.
				int wanted = ((piece + 1) * children) / SEGMENTS_PER_BRANCH - grown;

				for (int child = 0; child < wanted; child++) {
					double spread = Math.toRadians(settings.junctionAngleMinDegrees()
							+ (settings.junctionAngleMaxDegrees()
									- settings.junctionAngleMinDegrees())
									* Hashing.unitDouble(seed, hx + child, hz, SALT_ANGLE));
					double childSide = (grown + child) % 2 == 0 ? 1.0 : -1.0;

					// Spread along the piece rather than all leaving its start. Several
					// children from one point is a fan, and the renders showed exactly
					// that: starbursts scattered through the network where a hillside
					// should have had separate small valleys.
					double along = (child + 0.5) / wanted;
					double joinX = x + (nx - x) * along;
					double joinZ = z + (nz - z) * along;
					double joinElevation = elevation + (ne - elevation) * along;

					grow(joinX, joinZ, joinElevation, heading + childSide * spread,
							length / settings.lengthRatio(), childArea,
							Math.max(1, branchOrder - 1), levelsLeft - 1,
							streamIdAt(joinX, joinZ));
				}

				grown += wanted;

				x = nx;
				z = nz;
				elevation = ne;
			}
		}

		/**
		 * A creek's stream identity, hashed from where it leaves its parent.
		 *
		 * <p><b>Not a counter, and it was one.</b> A per-patch counter numbers the same
		 * creek differently depending on which patch generated it and in what order.
		 * Stream identity decides the divide, since the search keeps the two nearest
		 * <i>distinct</i> streams, so two adjacent columns on opposite sides of a patch
		 * boundary computed different hillslope positions and therefore different
		 * heights. That put hard rectangular seams across the whole world at 8,000-block
		 * spacing, invisible on an elevation ramp and unmistakable once cut depth was
		 * rendered on its own.
		 *
		 * <p>Hashing the junction position instead makes identity a property of the
		 * creek. Every patch that sees it agrees, because they are all asking the same
		 * question about the same place.
		 */
		private int streamIdAt(final double worldX, final double worldZ) {
			long hash = Hashing.hash(seed, Math.round(worldX), Math.round(worldZ), SALT_STREAM);

			return CREEK_STREAM_MARK | (int) (hash & 0x3FFF_FFFFL);
		}

		private void add(
				final double ax, final double az, final double bx, final double bz,
				final double ae, final double be, final double flow,
				final int branchOrder, final int streamId) {
			// Only what lands in this patch is kept. A creek is generated from its
			// anchor's coordinates, so the part crossing into the next patch is
			// regenerated identically there.
			if (Math.max(ax, bx) < originX || Math.min(ax, bx) > originX + PATCH_BLOCKS
					|| Math.max(az, bz) < originZ || Math.min(az, bz) > originZ + PATCH_BLOCKS) {
				return;
			}

			if (count == x0.length) {
				grow();
			}

			x0[count] = ax;
			z0[count] = az;
			x1[count] = bx;
			z1[count] = bz;
			e0[count] = ae;
			e1[count] = be;
			discharge[count] = flow;
			order[count] = branchOrder;
			stream[count] = streamId;
			count++;
		}

		private void grow() {
			int size = x0.length * 2;
			x0 = java.util.Arrays.copyOf(x0, size);
			z0 = java.util.Arrays.copyOf(z0, size);
			x1 = java.util.Arrays.copyOf(x1, size);
			z1 = java.util.Arrays.copyOf(z1, size);
			e0 = java.util.Arrays.copyOf(e0, size);
			e1 = java.util.Arrays.copyOf(e1, size);
			discharge = java.util.Arrays.copyOf(discharge, size);
			order = java.util.Arrays.copyOf(order, size);
			stream = java.util.Arrays.copyOf(stream, size);
		}

		Patch finish() {
			return new Patch(
					java.util.Arrays.copyOf(x0, count), java.util.Arrays.copyOf(z0, count),
					java.util.Arrays.copyOf(x1, count), java.util.Arrays.copyOf(z1, count),
					java.util.Arrays.copyOf(e0, count), java.util.Arrays.copyOf(e1, count),
					java.util.Arrays.copyOf(discharge, count),
					java.util.Arrays.copyOf(order, count),
					java.util.Arrays.copyOf(stream, count),
					branches, junctions, lengthSum, areaSum, perLevel.clone(),
					new double[] {logLengthSum, logAreaSum, logAreaSquaredSum, logProductSum});
		}
	}

	/** One patch of finished creek segments, in flat arrays for the query path. */
	public static final class Patch {
		private final double[] x0;
		private final double[] z0;
		private final double[] x1;
		private final double[] z1;
		private final double[] e0;
		private final double[] e1;
		private final double[] discharge;
		private final int[] order;
		private final int[] stream;

		private final int branches;
		private final int junctions;
		private final double lengthSum;
		private final double areaSum;
		private final int[] perLevel;
		private final double[] hackSums;

		Patch(final double[] x0, final double[] z0, final double[] x1, final double[] z1,
				final double[] e0, final double[] e1, final double[] discharge,
				final int[] order, final int[] stream,
				final int branches, final int junctions,
				final double lengthSum, final double areaSum, final int[] perLevel,
				final double[] hackSums) {
			this.x0 = x0;
			this.z0 = z0;
			this.x1 = x1;
			this.z1 = z1;
			this.e0 = e0;
			this.e1 = e1;
			this.discharge = discharge;
			this.order = order;
			this.stream = stream;
			this.branches = branches;
			this.junctions = junctions;
			this.lengthSum = lengthSum;
			this.areaSum = areaSum;
			this.perLevel = perLevel;
			this.hackSums = hackSums;
		}

		/**
		 * Regression sums for Hack's law: log length, log area, log area squared, and
		 * their product. The slope of log length against log area is the exponent.
		 */
		public double[] hackSums() {
			return hackSums;
		}

		/**
		 * Branches at each recursion level, smallest streams first.
		 *
		 * <p>Horton's bifurcation ratio is the ratio between successive entries here,
		 * and it is measured off this rather than assumed from the setting that asked
		 * for it.
		 */
		public int[] branchesPerLevel() {
			return perLevel;
		}

		public int segmentCount() {
			return x0.length;
		}

		public int branchCount() {
			return branches;
		}

		public int junctionCount() {
			return junctions;
		}

		public double totalLength() {
			double total = 0.0;

			for (int s = 0; s < x0.length; s++) {
				total += Math.hypot(x1[s] - x0[s], z1[s] - z0[s]);
			}

			return total;
		}

		public double meanBranchLength() {
			return branches == 0 ? 0.0 : lengthSum / branches;
		}

		public double meanBranchArea() {
			return branches == 0 ? 0.0 : areaSum / branches;
		}

		/**
		 * Merges this patch's creeks into an existing nearest-channel result.
		 *
		 * <p>Takes the result tier 2 already filled rather than producing its own, so a
		 * trunk and a creek compete on equal terms and the divide comes out of whichever
		 * two streams are genuinely closest.
		 *
		 * <p>Stream ids are offset past tier 2's so a creek can never be mistaken for the
		 * trunk it hangs off. Without that a point in the crook between a river and its
		 * own tributary would find one stream twice and report itself as a divide.
		 */
		public void mergeNearest(
				final double worldX, final double worldZ,
				final int streamOffset, final BasinNetwork.Nearest out) {
			for (int s = 0; s < x0.length; s++) {
				double dx = x1[s] - x0[s];
				double dz = z1[s] - z0[s];
				double lengthSquared = dx * dx + dz * dz;

				double t = lengthSquared <= 0.0
						? 0.0
						: Math.max(0.0, Math.min(1.0,
								((worldX - x0[s]) * dx + (worldZ - z0[s]) * dz) / lengthSquared));

				double nearX = x0[s] + dx * t;
				double nearZ = z0[s] + dz * t;
				double distance = Math.hypot(worldX - nearX, worldZ - nearZ);
				int id = streamOffset + stream[s];

				out.offer(distance, id, e0[s] + (e1[s] - e0[s]) * t, discharge[s], order[s]);
			}
		}
	}
}
