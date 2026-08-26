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
 * <p>A column resolves in three steps: tier 1 gives the outlet, the outlet gives the
 * basin solve, and the basin's spatial index gives the two nearest channels. Keying the
 * middle step by outlet rather than by position is what makes the answer stable, since
 * every column in a basin reaches the identical solve.
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

		network.offerNear(worldX, worldZ, nearest);

		if (withCreeks) {
			creeks.patchAt(worldX, worldZ, network)
					.mergeNearest(worldX, worldZ, network.streamCount(), nearest);
		}

		if (!nearest.found()) {
			double budget = uplift.heightAt(worldX, worldZ);

			// No channel, but there can still be a lake: a closed depression with no
			// stream reaching it is exactly where standing water collects.
			return new DrainageSample(budget, Double.MAX_VALUE, 0.0, 0, 1.0,
					network.lakeSurfaceAt(worldX, worldZ), network.endorheicAt(worldX, worldZ));
		}

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
