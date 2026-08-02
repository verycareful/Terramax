package com.fury.terramax.core.plate;

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
 * in sequence. This uses a stateless jittered grid instead, so a lookup is around
 * 25 hashes with no allocation and no ordering dependency. Caching that would cost
 * more in memory and save-format compatibility than it saves in arithmetic. If
 * profiling later disagrees, a cache can be added in front of this class without
 * changing anything that calls it.
 */
public final class PlateMap {
	/** Decorrelates the several independent draws made per plate. */
	private static final long SALT_TYPE = 11L;
	private static final long SALT_ELEVATION = 12L;
	private static final long SALT_MOTION_ANGLE = 13L;
	private static final long SALT_MOTION_SPEED = 14L;

	/**
	 * Slowest a plate may move, as a fraction of the fastest.
	 *
	 * <p>Not zero: a stationary plate would classify every one of its boundaries
	 * purely by its neighbours' motion, which produces implausibly uniform results
	 * around it.
	 */
	private static final double MIN_MOTION_SPEED = 0.35;

	private final long seed;
	private final PlateMapSettings settings;
	private final VoronoiSolver voronoi;

	public PlateMap(final long seed, final PlateMapSettings settings) {
		this.seed = seed;
		this.settings = settings;
		this.voronoi = new VoronoiSolver(
				new PoissonDisk(seed, settings.spacingBlocks(), settings.jitter()));
	}

	public PlateMapSettings settings() {
		return settings;
	}

	public VoronoiSolver voronoi() {
		return voronoi;
	}

	/** The plate owning the given cell. Pure function of seed and coordinate. */
	public Plate plateAt(final long cellX, final long cellZ) {
		PlateType type = Hashing.unitDouble(seed, cellX, cellZ, SALT_TYPE) < settings.continentalFraction()
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
		VoronoiSample cell = voronoi.sample(worldX, worldZ);

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
