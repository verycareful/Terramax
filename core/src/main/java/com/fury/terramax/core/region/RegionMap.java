package com.fury.terramax.core.region;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.util.DomainWarp;
import com.fury.terramax.core.util.Hashing;
import com.fury.terramax.core.util.PoissonDisk;
import com.fury.terramax.core.util.VoronoiSample;
import com.fury.terramax.core.util.VoronoiSolver;

/**
 * The third lattice: resolves a world position to a region.
 *
 * <p>Plates and crust cells decide the tectonic story. Regions decide what the
 * ground <em>between</em> the mountains looks like, which nothing else in the
 * system does. Without this a continental interior is one flat plain from edge to
 * edge, because a crust cell's base elevation is a single number and fractal detail
 * is invisible across tens of thousands of blocks.
 *
 * <p>This supersedes the diffuse {@code interiorRelief} noise it replaces. Noise
 * can make lumps, and lumps are not landforms. A region is a coherent area with a
 * type, a height and an identity, which is what a biome can be placed on.
 *
 * <p><b>Tectonics overwrite regions, not the other way round.</b> This map is
 * consulted for the interior; where a plate boundary builds a range, the range
 * wins.
 */
public final class RegionMap {
	/** Decorrelates the several independent draws made per region. */
	private static final long SALT_TYPE = 0x84222325CBF29CE4L;
	private static final long SALT_HEIGHT = 0x1B873593CC9E2D51L;

	/** Keeps this lattice's jitter and warp independent of the plate lattices'. */
	private static final long SALT_LATTICE = 0xC2B2AE3D27D4EB4FL;

	private final long seed;
	private final RegionSettings settings;
	private final VoronoiSolver voronoi;
	private final DomainWarp warp;

	public RegionMap(final long seed, final RegionSettings settings) {
		this.seed = seed;
		this.settings = settings;
		this.voronoi = new VoronoiSolver(
				new PoissonDisk(seed ^ SALT_LATTICE, settings.spacingBlocks(), settings.jitter()));
		this.warp = new DomainWarp(
				seed ^ SALT_LATTICE,
				settings.warpStrengthBlocks(),
				settings.warpWavelengthBlocks(),
				settings.warpOctaves());
	}

	public RegionSettings settings() {
		return settings;
	}

	public VoronoiSolver voronoi() {
		return voronoi;
	}

	/**
	 * The region with the given identity, on the given crust.
	 *
	 * <p>Crust type gates which types are possible: oceanic crust yields ocean floor
	 * and nothing else. In the full design lithology, climate and past ice gate
	 * further, which is why the types needing those are absent from
	 * {@link RegionType} rather than present and ungated.
	 */
	public Region regionAt(final long cellX, final long cellZ, final CrustType crust) {
		RegionType type = crust.isContinental()
				? continentalType(cellX, cellZ)
				: RegionType.OCEAN_FLOOR;

		double t = Hashing.unitDouble(seed, cellX, cellZ, SALT_HEIGHT);
		double height = type.minHeight() + (type.maxHeight() - type.minHeight()) * t;

		return new Region(cellX, cellZ, type, height, type.reliefAmplitude());
	}

	/** Weighted draw over the continental types. */
	private RegionType continentalType(final long cellX, final long cellZ) {
		double roll = Hashing.unitDouble(seed, cellX, cellZ, SALT_TYPE)
				* RegionType.continentalWeightSum();

		double cumulative = 0.0;

		for (RegionType candidate : RegionType.CONTINENTAL) {
			cumulative += candidate.weight();

			if (roll < cumulative) {
				return candidate;
			}
		}

		// Unreachable while every weight is positive, but a total function beats a
		// null that surfaces ten thousand blocks away as a crash inside the renderer.
		return RegionType.PLAIN;
	}

	/**
	 * Resolves the region owning a position, its neighbour, and the distance to the
	 * boundary between them.
	 *
	 * <p>Queries are warped before the lattice is consulted, for the same reason
	 * plate queries are: unwarped Voronoi gives convex polygons, and no landform on
	 * Earth is a convex polygon.
	 *
	 * <p>Both the region and its neighbour are resolved on the <em>same</em> crust
	 * type, the one under the query. Resolving the neighbour on its own crust would
	 * be more accurate, but it would also make a coastline blend a plain into an
	 * abyssal plain across 500 blocks, which is not what a coast looks like. Crust
	 * type changes at crust cell edges, and those are the plate system's business.
	 */
	public RegionSample sample(final double worldX, final double worldZ, final CrustType crust) {
		VoronoiSample cell = voronoi.sample(
				warp.warpX(worldX, worldZ),
				warp.warpZ(worldX, worldZ));

		return new RegionSample(
				regionAt(cell.cellX(), cell.cellZ(), crust),
				regionAt(cell.neighbourCellX(), cell.neighbourCellZ(), crust),
				cell.boundaryDistance());
	}
}
