package com.fury.terramax.core.plate;

import com.fury.terramax.core.util.DomainWarp;
import com.fury.terramax.core.util.FractalNoise2D;
import com.fury.terramax.core.util.Hashing;
import com.fury.terramax.core.util.PoissonDisk;
import com.fury.terramax.core.util.VoronoiSample;
import com.fury.terramax.core.util.VoronoiSolver;

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
	 * Samples per axis used to calibrate the land/ocean threshold. 64x64 is 4096
	 * evaluations, a few milliseconds once, and enough for a stable quantile.
	 */
	private static final int CALIBRATION_GRID = 64;

	/** Area calibrated over, in plate spacings. Wide enough to span many continents. */
	private static final double CALIBRATION_SPAN_FACTOR = 64.0;

	private final long seed;
	private final PlateMapSettings settings;
	private final VoronoiSolver voronoi;
	private final DomainWarp warp;
	private final FractalNoise2D continentField;
	private final double continentThreshold;

	public PlateMap(final long seed, final PlateMapSettings settings) {
		this.seed = seed;
		this.settings = settings;
		this.voronoi = new VoronoiSolver(
				new PoissonDisk(seed, settings.spacingBlocks(), settings.jitter()));
		this.warp = new DomainWarp(
				seed,
				settings.warpStrengthBlocks(),
				settings.warpWavelengthBlocks(),
				settings.warp().octaves());
		this.continentField = FractalNoise2D.standard(
				seed ^ SALT_CONTINENT, CONTINENT_OCTAVES, settings.continentWavelengthBlocks());
		this.continentThreshold = calibrateContinentThreshold();
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
		double span = settings.spacingBlocks() * CALIBRATION_SPAN_FACTOR;
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

	public DomainWarp warp() {
		return warp;
	}

	/**
	 * The plate owning the given cell. Pure function of seed and coordinate.
	 *
	 * <p>Type comes from a low-frequency field sampled at the plate's centre, not
	 * from an independent per-plate draw. An independent draw makes each plate flip
	 * its own coin, which mixes land and ocean uniformly and produces isolated seas
	 * ringed by land instead of continents and oceans.
	 */
	public Plate plateAt(final long cellX, final long cellZ) {
		double siteX = voronoi.sites().pointX(cellX, cellZ);
		double siteZ = voronoi.sites().pointZ(cellX, cellZ);

		PlateType type = continentField.sampleUnit(siteX, siteZ) < continentThreshold
				? PlateType.CONTINENTAL
				: PlateType.OCEANIC;

		int base = type == PlateType.CONTINENTAL
				? settings.continentalBase()
				: settings.oceanicBase();

		// Centred on the type's base, spread across +/- baseVariation.
		double variation = (Hashing.unitDouble(seed, cellX, cellZ, SALT_ELEVATION) - 0.5)
				* 2.0 * settings.baseVariation();

		double angle = Hashing.unitDouble(seed, cellX, cellZ, SALT_MOTION_ANGLE) * Math.TAU;
		double speed = MIN_MOTION_SPEED
				+ (1.0 - MIN_MOTION_SPEED) * Hashing.unitDouble(seed, cellX, cellZ, SALT_MOTION_SPEED);

		return new Plate(
				cellX, cellZ,
				type,
				base + variation,
				Math.cos(angle) * speed,
				Math.sin(angle) * speed);
	}

	/**
	 * Resolves the owning plate, its nearest neighbour, and the nature of the
	 * boundary between them.
	 */
	public PlateSample sample(final double worldX, final double worldZ) {
		double queryX = warp.warpX(worldX, worldZ);
		double queryZ = warp.warpZ(worldX, worldZ);

		VoronoiSample cell = voronoi.sample(queryX, queryZ);

		Plate plate = plateAt(cell.cellX(), cell.cellZ());
		Plate neighbour = plateAt(cell.neighbourCellX(), cell.neighbourCellZ());

		double neighbourX = voronoi.sites().pointX(cell.neighbourCellX(), cell.neighbourCellZ());
		double neighbourZ = voronoi.sites().pointZ(cell.neighbourCellX(), cell.neighbourCellZ());

		double axisX = neighbourX - cell.siteX();
		double axisZ = neighbourZ - cell.siteZ();
		double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);

		if (axisLength == 0.0) {
			return new PlateSample(
					plate, neighbour, PlateBoundaryType.TRANSFORM, cell.boundaryDistance(), 0.0, 0.0);
		}

		// Unit normal pointing from this plate toward its neighbour, and the
		// tangent along the boundary.
		double normalX = axisX / axisLength;
		double normalZ = axisZ / axisLength;

		// Relative motion of this plate with respect to its neighbour. Querying from
		// the far side flips both the normal and the relative velocity, so the signs
		// cancel and a boundary classifies identically from either side.
		double relativeX = plate.motionX() - neighbour.motionX();
		double relativeZ = plate.motionZ() - neighbour.motionZ();

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

		return new PlateSample(plate, neighbour, type, cell.boundaryDistance(), convergence, shear);
	}
}
