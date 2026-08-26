package com.fury.terramax.core.fluvial;

import com.fury.terramax.core.terrain.HeightField;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 1. Answers exactly one question: which outlet does this point drain to.
 *
 * <p><b>Basins are keyed by outlet cell, not by tile, and that is what kills the
 * seam.</b> A basin straddling a tile boundary gets resolved by two different tiles.
 * Because each tile carries a margin wider than the largest possible basin, both tiles
 * contain the whole basin, both route it identically, and both therefore arrive at the
 * same outlet cell. Basin identity becomes a property of the world rather than of
 * which tile happened to ask, so two adjacent columns in one basin always receive the
 * identical tier 2 solve.
 *
 * <p>Keying by tile instead would put a straight seam along every tile boundary in the
 * world, and it would show immediately in a basin render as a colour break running
 * dead straight through terrain that has no straight lines in it.
 *
 * <p><b>The margin is an assumption, and it is measured rather than trusted.</b> It
 * rests on continents correlating over roughly 240,000 blocks, so a basin should never
 * approach 256,000 across. If one does, two tiles could genuinely disagree. The
 * simulator reports the largest basin found for exactly this reason.
 *
 * <p>Tier 1 runs at 8,000 blocks because that is all this question needs. Which way a
 * basin drains is decided by tens of thousands of blocks of relief, not by whether a
 * particular ridge is 900 blocks wide. The fine work happens in tier 2, bounded to one
 * basin, where it is affordable.
 *
 * <p><b>It routes the tectonic surface, not the full uplift surface, and that was
 * measured rather than assumed.</b> Region relief has a 2,300-block wavelength and this
 * tier samples at 8,000, so it cannot represent that term whatever it does with it.
 * Routing both over the same tile gave the same drainage: 891 basins against 966,
 * median 6 cells either way, largest 86,000 against 83,000 blocks across, and the ten
 * largest holding 8.9 percent of land in both. Region relief was not carrying
 * information here.
 *
 * <p>It was carrying cost. The uplift surface includes {@code RegionMap}, which is
 * gated on moisture, so sampling it at province scale dragged in every moisture gating
 * node across a 1,024,000-block extent and took 18 seconds per tile. Tier 2 samples at
 * 1,000 blocks, does resolve regions, and routes the full uplift surface, which is
 * where a plateau genuinely does redirect a river.
 */
public final class BasinIndex {
	private final HeightField surface;
	private final DrainageSettings settings;
	private final Map<Long, long[]> tiles = new ConcurrentHashMap<>();

	public BasinIndex(final HeightField surface, final DrainageSettings settings) {
		this.surface = surface;
		this.settings = settings;
	}

	public DrainageSettings settings() {
		return settings;
	}

	/** Packs a global lattice cell into one key, as {@code MoistureField} packs nodes. */
	public static long packCell(final int cellX, final int cellZ) {
		return (cellX & 0xFFFFFFFFL) << 32 | (cellZ & 0xFFFFFFFFL);
	}

	public static int unpackCellX(final long key) {
		return (int) (key >>> 32);
	}

	public static int unpackCellZ(final long key) {
		return (int) key;
	}

	public double outletWorldX(final long key) {
		return (unpackCellX(key) + 0.5) * settings.provinceLatticeBlocks();
	}

	public double outletWorldZ(final long key) {
		return (unpackCellZ(key) + 0.5) * settings.provinceLatticeBlocks();
	}

	/**
	 * True where this outlet is genuinely below base level, so the basin reaches the
	 * sea. False means a closed sink, which is where an endorheic basin comes from.
	 */
	public boolean reachesSea(final long key) {
		return surface.heightAt(outletWorldX(key), outletWorldZ(key)) <= settings.baseLevelY();
	}

	/** The canonical outlet of the basin containing this point. */
	public long outletAt(final double worldX, final double worldZ) {
		long tileX = Math.floorDiv((long) Math.floor(worldX), (long) settings.provinceTileBlocks());
		long tileZ = Math.floorDiv((long) Math.floor(worldZ), (long) settings.provinceTileBlocks());

		long[] outlets = tileOutlets(tileX, tileZ);

		int tileCells = settings.provinceTileCells();
		double tileOriginX = tileX * settings.provinceTileBlocks();
		double tileOriginZ = tileZ * settings.provinceTileBlocks();

		int ix = clamp((int) Math.floor(
				(worldX - tileOriginX) / settings.provinceLatticeBlocks()), tileCells);
		int iz = clamp((int) Math.floor(
				(worldZ - tileOriginZ) / settings.provinceLatticeBlocks()), tileCells);

		return outlets[iz * tileCells + ix];
	}

	/** How many distinct basins a tile contains, for the statistics. */
	public int basinCountIn(final double worldX, final double worldZ) {
		long tileX = Math.floorDiv((long) Math.floor(worldX), (long) settings.provinceTileBlocks());
		long tileZ = Math.floorDiv((long) Math.floor(worldZ), (long) settings.provinceTileBlocks());

		return (int) java.util.Arrays.stream(tileOutlets(tileX, tileZ)).distinct().count();
	}

	private long[] tileOutlets(final long tileX, final long tileZ) {
		long tileKey = packCell((int) tileX, (int) tileZ);
		long[] outlets = tiles.get(tileKey);

		if (outlets != null) {
			return outlets;
		}

		// Deliberate get-then-put rather than computeIfAbsent. A province solve is
		// 16,384 uplift samples and computeIfAbsent would hold a bin lock across all
		// of it, blocking every unrelated tile that hashes to the same bin. Two
		// threads racing on the same tile is the cheaper problem.
		long[] solved = solveTile(tileX, tileZ);
		long[] existing = tiles.putIfAbsent(tileKey, solved);

		return existing != null ? existing : solved;
	}

	private static int clamp(final int value, final int limit) {
		return Math.max(0, Math.min(limit - 1, value));
	}

	private long[] solveTile(final long tileX, final long tileZ) {
		double spacing = settings.provinceLatticeBlocks();
		int marginCells = settings.provinceMarginCells();
		int tileCells = settings.provinceTileCells();
		int extent = settings.provinceExtentCells();

		double originX = tileX * settings.provinceTileBlocks() - settings.provinceMarginBlocks();
		double originZ = tileZ * settings.provinceTileBlocks() - settings.provinceMarginBlocks();

		FlowLattice flow = new FlowLattice(extent, extent, spacing, originX, originZ);
		flow.sampleSurface(surface);
		flow.floodFill(settings.baseLevelY());
		flow.route();

		long[] outlets = new long[tileCells * tileCells];

		// The lattice origin in global cell coordinates, so an outlet's key is the
		// same value no matter which tile resolved it. This is the whole point.
		long globalOriginX = Math.round(originX / spacing);
		long globalOriginZ = Math.round(originZ / spacing);

		for (int iz = 0; iz < tileCells; iz++) {
			for (int ix = 0; ix < tileCells; ix++) {
				int local = flow.index(ix + marginCells, iz + marginCells);
				int outlet = flow.outletOf(local);

				outlets[iz * tileCells + ix] = packCell(
						(int) (globalOriginX + flow.cellX(outlet)),
						(int) (globalOriginZ + flow.cellZ(outlet)));
			}
		}

		return outlets;
	}
}
