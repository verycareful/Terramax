package com.fury.terramax.core.fluvial;

import com.fury.terramax.core.climate.MoistureField;
import com.fury.terramax.core.terrain.HeightField;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole drainage subsystem behind one lookup.
 *
 * <p>The only type {@code TerrainHeight} talks to. Which tier answered, and how many
 * lattices had to be solved to get there, is nobody else's concern.
 *
 * <p>A column resolves in three steps: tier 1 gives the outlets in reach, each outlet
 * gives a basin solve, and every basin's spatial index offers into one shared search.
 * Keying the middle step by outlet rather than by position is what makes each solve
 * stable, since every column in a basin reaches the identical one.
 *
 * <p><b>Plural, and it was singular.</b> Searching only the basin the lattice assigned
 * made the finished height a step function on the 8,000-block assignment grid: columns
 * four blocks apart across a grid line differed by 9.4 blocks on average against 1.8 in
 * open ground, drawing rectangular seams across the whole world. Offering every basin
 * in reach removes the dependence on which basin was assigned, so position is the only
 * thing the answer depends on.
 */
public final class DrainageMap {
	private final HeightField uplift;
	private final MoistureField moisture;
	private final BasinIndex basins;
	private final DrainageSettings settings;
	private final CreekTrees creeks;

	/**
	 * Basin solves, guarded so only one thread builds any given basin.
	 *
	 * <p><b>Different from how {@code MoistureField} caches, and deliberately so.</b>
	 * That class uses a plain get-then-put because a duplicated node solve is cheap and
	 * holding a bin lock across one would be worse. A basin is 20,000 uplift samples
	 * and a second of work, so duplicating it is the expensive mistake. A future per
	 * key serialises only the threads racing for the same basin and leaves every other
	 * basin free, which {@code computeIfAbsent} would not: that holds a bin lock, so an
	 * unrelated basin hashing to the same bin would block behind it.
	 */
	private final Map<Long, CompletableFuture<BasinNetwork>> networks = new ConcurrentHashMap<>();

	/**
	 * Insertion order, so the cache can be bounded without a lock.
	 *
	 * <p>Approximately first-in-first-out rather than least-recently-used. Proper LRU
	 * needs the read path to record every access, which would put a write on the hottest
	 * path in the generator to protect against a case that does not arise: a player is
	 * in one basin and its neighbours, so the working set is a handful of entries
	 * against a limit of twenty-four. The bound exists to stop a world-spanning render
	 * holding every basin it ever touched, not to optimise a hit rate.
	 */
	private final java.util.Queue<Long> networkOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** Reused per thread. The query runs for every column and must not allocate. */
	private final ThreadLocal<BasinNetwork.Nearest> scratch =
			ThreadLocal.withInitial(BasinNetwork.Nearest::new);

	/** The distinct basins in reach of one column. Nine probes, so at most nine. */
	private final ThreadLocal<long[]> reach = ThreadLocal.withInitial(() -> new long[9]);

	public DrainageMap(
			final long seed, final HeightField uplift, final MoistureField moisture,
			final BasinIndex basins, final DrainageSettings settings) {
		this.uplift = uplift;
		this.moisture = moisture;
		this.basins = basins;
		this.settings = settings;
		this.creeks = new CreekTrees(seed, uplift, settings);
	}

	public CreekTrees creeks() {
		return creeks;
	}

	public DrainageSettings settings() {
		return settings;
	}

	public BasinIndex basins() {
		return basins;
	}

	/** How many basins have been solved, for the cost reporting. */
	public int solvedBasins() {
		return networks.size();
	}

	/** The basin covering this point, solved once and shared. */
	public BasinNetwork networkAt(final double worldX, final double worldZ) {
		return network(basins.outletAt(worldX, worldZ));
	}

	public BasinNetwork network(final long outletKey) {
		CompletableFuture<BasinNetwork> pending = networks.get(outletKey);

		if (pending != null) {
			return pending.join();
		}

		CompletableFuture<BasinNetwork> mine = new CompletableFuture<>();
		CompletableFuture<BasinNetwork> existing = networks.putIfAbsent(outletKey, mine);

		if (existing != null) {
			return existing.join();
		}

		try {
			BasinNetwork solved = new BasinNetwork(outletKey, uplift, moisture, basins, settings);
			mine.complete(solved);
			networkOrder.add(outletKey);
			evictOldest();

			return solved;
		} catch (RuntimeException failure) {
			// Never leave an uncompleted future in the map, or every later reader of
			// this basin blocks on it forever and world generation stops.
			networks.remove(outletKey, mine);
			mine.completeExceptionally(failure);

			throw failure;
		}
	}

	private void evictOldest() {
		while (networks.size() > settings.basinCacheLimit()) {
			Long oldest = networkOrder.poll();

			if (oldest == null) {
				return;
			}

			networks.remove(oldest);
		}
	}

	/**
	 * Everything the carve needs to know about drainage at one column.
	 *
	 * <p>Both tiers feed one search rather than being merged afterwards. That matters
	 * for the divide: standing between a trunk and one of its own creeks, the two
	 * nearest streams come from different tiers, and searching them separately would
	 * never see that pair.
	 */
	public DrainageSample sample(final double worldX, final double worldZ) {
		return sample(worldX, worldZ, true);
	}

	/**
	 * The same lookup, with tier 3 optionally left out.
	 *
	 * <p><b>A viewing concern, not a world one.</b> The game always asks for creeks; this
	 * exists so the simulator does not have to build them for a picture that cannot show
	 * them. At continental scale a pixel is 820 blocks and a creek is a few blocks wide,
	 * so every creek in view is sub-pixel, and building them cost seven minutes for one
	 * render that displayed none of them.
	 *
	 * <p>Same reasoning as {@code MoistureScale.forResolution}, which already solves
	 * moisture no finer than the screen can show. Both cases are the renderer choosing
	 * a resolution, never the world changing what it contains.
	 */
	public DrainageSample sample(
			final double worldX, final double worldZ, final boolean withCreeks) {
		BasinNetwork network = networkAt(worldX, worldZ);
		BasinNetwork.Nearest nearest = scratch.get();

		nearest.reset();
		nearest.fallbackHalfSpacing(withCreeks
				? settings.creekSpacingBlocks() * 0.5
				: settings.channelSpacingTargetBlocks() * 0.5);

		long[] basinsInReach = reach.get();
		int found = gatherReach(worldX, worldZ, network.outletKey(), basinsInReach);

		for (int i = 0; i < found; i++) {
			BasinNetwork other = basinsInReach[i] == network.outletKey()
					? network
					: network(basinsInReach[i]);

			other.offerNear(worldX, worldZ, nearest);

			if (withCreeks) {
				creeks.mergeNear(worldX, worldZ, other,
						settings.creekSpacingBlocks() * 0.5, nearest);
			}
		}

		if (!nearest.found()) {
			double budget = uplift.heightAt(worldX, worldZ);

			// No channel, but there can still be a lake: a closed depression with no
			// stream reaching it is exactly where standing water collects.
			return new DrainageSample(budget, Double.MAX_VALUE, 0.0, 0, 1.0,
					network.lakeSurfaceAt(worldX, worldZ), network.endorheicAt(worldX, worldZ));
		}

		// Channels come from every basin in reach; standing water comes from the one
		// the point is in. A lake belongs to a single basin by definition, since it is
		// the flooded part of one depression, so there is nothing to merge. The step at a
		// basin boundary is a step between one lake and no lake, which is what a shoreline
		// is.
		return new DrainageSample(
				nearest.elevation1,
				nearest.distance1,
				nearest.discharge1,
				nearest.order1,
				nearest.hillslope(),
				network.lakeSurfaceAt(worldX, worldZ),
				network.endorheicAt(worldX, worldZ));
	}

	/**
	 * The distinct basins whose channels could be the nearest to a column.
	 *
	 * <p><b>A column is not confined to the basin the lattice assigned it, and this is
	 * what removes the seam.</b> Basin identity is a lookup into an 8,000-block grid, so
	 * it is a step function with its steps on that grid rather than on any divide.
	 * Searching only the assigned basin made the finished height a step function too:
	 * measured at a mean jump of 9.4 blocks between columns four blocks apart across a
	 * grid line, against 1.8 blocks in open ground.
	 *
	 * <p>Offering every basin in reach into one search makes the answer a function of
	 * position alone. The transition now falls where the two nearest streams are
	 * genuinely equidistant, which is the divide, and {@code hillslope} reaches 1 there
	 * on both sides because it can finally see both streams. Continuous by construction
	 * rather than by smoothing.
	 *
	 * <p>Probed at the search radius rather than at every neighbouring cell, since a
	 * channel further away than that cannot win. Adjacent cells share an outlet about
	 * three quarters of the time, so the common case costs nine lattice lookups and one
	 * basin, not nine basins.
	 */
	private int gatherReach(
			final double worldX, final double worldZ, final long home, final long[] out) {
		double radius = settings.channelSpacingTargetBlocks() * 0.5;

		out[0] = home;
		int found = 1;

		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0) {
					continue;
				}

				long key = basins.outletAt(worldX + dx * radius, worldZ + dz * radius);
				boolean seen = false;

				for (int i = 0; i < found; i++) {
					if (out[i] == key) {
						seen = true;
						break;
					}
				}

				if (!seen) {
					out[found++] = key;
				}
			}
		}

		return found;
	}

	/**
	 * Blocks per pixel below which creeks are worth building.
	 *
	 * <p>A tenth of the creek spacing, not half of it. Half put the threshold at 1,000
	 * blocks per pixel and a continental view is 820, so it passed and built every creek
	 * in view to draw none of them. Individual creeks only resolve when a pixel is small
	 * against the distance between them, and a creek channel is a few blocks wide
	 * regardless.
	 */
	public double creekVisibleBelowBlocks() {
		return settings.creekSpacingBlocks() * 0.1;
	}

	/** The basin covering this point, for callers that need more than a sample. */
	public boolean playaAt(final double worldX, final double worldZ) {
		return networkAt(worldX, worldZ).playaAt(worldX, worldZ);
	}

	public boolean terminalLakeAt(final double worldX, final double worldZ) {
		return networkAt(worldX, worldZ).terminalLakeAt(worldX, worldZ);
	}
}
