package com.fury.terramax.core.region;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.util.DomainWarp;
import com.fury.terramax.core.util.Equaliser;
import com.fury.terramax.core.util.FractalNoise2D;
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
	private static final long SALT_PROVINCE = 0x6A09E667F3BCC909L;
	private static final long SALT_CHARACTER = 0xBB67AE8584CAA73BL;

	/** Octaves in the province field. Few: a province is a broad tilt, not detail. */
	private static final int PROVINCE_OCTAVES = 3;

	/** Area the province fields are calibrated over, in their own wavelengths. */
	private static final double CALIBRATION_SPAN_FACTOR = 400.0;

	/** Keeps this lattice's jitter and warp independent of the plate lattices'. */
	private static final long SALT_LATTICE = 0xC2B2AE3D27D4EB4FL;

	private final long seed;
	private final RegionSettings settings;
	private final VoronoiSolver voronoi;
	private final DomainWarp warp;
	private final RegionClimate climate;
	private final FractalNoise2D province;
	private final FractalNoise2D character;
	private final Equaliser provinceScale;
	private final Equaliser characterScale;

	/**
	 * @param climate what decides which landforms survive here; pass
	 *                {@link RegionClimate#NEUTRAL} where no climate model exists yet
	 */
	public RegionMap(
			final long seed, final RegionSettings settings, final RegionClimate climate) {
		this.seed = seed;
		this.settings = settings;
		this.climate = climate;
		this.province = FractalNoise2D.standard(
				seed ^ SALT_PROVINCE, PROVINCE_OCTAVES, settings.provinceWavelengthBlocks());
		this.character = FractalNoise2D.standard(
				seed ^ SALT_CHARACTER, PROVINCE_OCTAVES, settings.provinceWavelengthBlocks());

		// Both fields are read as positions in a table, so both must be spread evenly
		// first. Without this the middle of each table takes almost everything and its
		// two ends are unreachable: see Equaliser.
		double span = settings.provinceWavelengthBlocks() * CALIBRATION_SPAN_FACTOR;

		this.provinceScale = Equaliser.calibrate(province, seed ^ SALT_PROVINCE, span);
		this.characterScale = Equaliser.calibrate(character, seed ^ SALT_CHARACTER, span);
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
	 * and nothing else. Climate gates further, by dryness. Lithology and past ice will
	 * gate further still, which is why the types needing those are absent from
	 * {@link RegionType} rather than present and ungated.
	 *
	 * <p><b>Everything is decided at the region's own centre, not at the query
	 * point.</b> A region has one type and one height across its whole area, so
	 * sampling climate or the province field per column would let one region disagree
	 * with itself about what it is.
	 */
	public Region regionAt(final long cellX, final long cellZ, final CrustType crust) {
		double siteX = voronoi.sites().pointX(cellX, cellZ);
		double siteZ = voronoi.sites().pointZ(cellX, cellZ);

		RegionType type = crust.isContinental()
				? continentalType(cellX, cellZ, siteX, siteZ, climate.aridityAt(siteX, siteZ))
				: RegionType.OCEAN_FLOOR;

		double height = type.minHeight() + (type.maxHeight() - type.minHeight())
				* heightFraction(cellX, cellZ, siteX, siteZ);

		return new Region(cellX, cellZ, type, height, type.reliefAmplitude());
	}

	/**
	 * Where in its type's band this region sits, from a province field plus a roll.
	 *
	 * <p><b>The province field is what stops adjacent plateaus standing at 150 and
	 * 850.</b> Height used to be a pure per-cell hash, which is white noise: two
	 * neighbouring regions of the same type had no more reason to agree than two on
	 * opposite sides of the world. Real neighbouring plateaus share a level because
	 * they are the same uplifted surface, and a smooth field an order of magnitude
	 * wider than a region reproduces that directly.
	 *
	 * <p>The per-cell roll is kept as a minority share so a province is not perfectly
	 * flat. Without it a plateau province becomes one enormous table, which is the
	 * opposite failure and just as artificial.
	 */
	private double heightFraction(
			final long cellX, final long cellZ, final double siteX, final double siteZ) {
		double broad = provinceScale.uniform(province.sample(siteX, siteZ));
		double roll = Hashing.unitDouble(seed, cellX, cellZ, SALT_HEIGHT);
		double share = settings.provinceWeight();

		return Math.max(0.0, Math.min(1.0, broad * share + roll * (1.0 - share)));
	}

	/** Where this region sits on the landform axis, from the smooth field plus a roll. */
	private double characterFraction(
			final long cellX, final long cellZ, final double siteX, final double siteZ) {
		double broad = characterScale.uniform(character.sample(siteX, siteZ));
		double roll = Hashing.unitDouble(seed, cellX, cellZ, SALT_TYPE);
		double share = settings.provinceWeight();

		return Math.max(0.0, Math.min(1.0, broad * share + roll * (1.0 - share)));
	}

	/**
	 * The region's type: a position on a smooth landform axis, not an independent roll.
	 *
	 * <p><b>This is the fix for the patchwork, and gating on climate alone was not
	 * it.</b> Climate makes the distribution vary across the world, but each region
	 * still drew from that distribution on its own, and biasing a coin does not make
	 * neighbouring flips agree. A 900-block plateau could still land beside a 5-block
	 * plain, because nothing connected the two draws.
	 *
	 * <p>So the draw is taken from a smooth field sampled at the region's centre
	 * instead of from its cell hash. Neighbouring regions read almost the same value
	 * and therefore land in almost the same place on the axis, which is what makes a
	 * plateau country and a hill country rather than confetti. A minority share of the
	 * old per-cell roll survives so a province still holds variety; without it a
	 * province becomes one type edge to edge, which is the opposite failure.
	 *
	 * <p>{@link RegionType#CONTINENTAL} is ordered so that neighbouring entries are
	 * plausible neighbours on the ground, because a smooth field crossing between them
	 * puts them side by side. Flat, to gently rolling, to hilly, to high and flat, to
	 * tabled, to domed: the two arid types sit together at the far end, so a mesa
	 * country borders inselberg country rather than a floodplain.
	 *
	 * <p>Climate still gates, and now it does the job it is actually suited to: it
	 * sets how wide each type's share of the axis is, so the same field crossing the
	 * same value yields hills in the wet half of a continent and mesas in the dry.
	 */
	private RegionType continentalType(
			final long cellX, final long cellZ,
			final double siteX, final double siteZ, final double aridity) {
		double roll = characterFraction(cellX, cellZ, siteX, siteZ)
				* RegionType.continentalWeightSum(aridity);

		double cumulative = 0.0;

		for (RegionType candidate : RegionType.CONTINENTAL) {
			cumulative += candidate.weightAt(aridity);

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
