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

			return solved;
		} catch (RuntimeException failure) {
			// Never leave an uncompleted future in the map, or every later reader of
			// this basin blocks on it forever and world generation stops.
			networks.remove(outletKey, mine);
			mine.completeExceptionally(failure);

			throw failure;
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
		BasinNetwork network = networkAt(worldX, worldZ);
		BasinNetwork.Nearest nearest = scratch.get();

		nearest.reset();
		nearest.fallbackHalfSpacing(settings.creekSpacingBlocks() * 0.5);

		network.offerNear(worldX, worldZ, nearest);
		creeks.patchAt(worldX, worldZ, network)
				.mergeNearest(worldX, worldZ, network.streamCount(), nearest);

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

	/** The basin covering this point, for callers that need more than a sample. */
	public boolean playaAt(final double worldX, final double worldZ) {
		return networkAt(worldX, worldZ).playaAt(worldX, worldZ);
	}

	public boolean terminalLakeAt(final double worldX, final double worldZ) {
		return networkAt(worldX, worldZ).terminalLakeAt(worldX, worldZ);
	}
}
