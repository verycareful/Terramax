package com.fury.terramax.sim;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.plate.PlateBoundaryType;
import com.fury.terramax.core.region.RegionType;

/**
 * Measurements of a region of the world.
 *
 * <p>These exist because pictures hide defects that numbers surface immediately.
 * Three separate bugs in this project were invisible on the rendered maps and obvious
 * in the statistics: a transform share of 89%, a land fraction of 78% against a 62%
 * target, and terrain reaching y=-367 through the floor of the world.
 *
 * <p>Sampled on a coarse grid rather than per pixel. Every proportion here is stable
 * well before the grid is dense enough to cost anything.
 */
public record TerrainStatistics(
		int samples,
		double sampleSpacing,
		Map<CrustType, Integer> crustCounts,
		Map<PlateBoundaryType, Integer> boundaryCounts,
		Map<RegionType, Integer> regionCounts,
		int plateCount,
		double smallestPlateWidth,
		double medianPlateWidth,
		double largestPlateWidth,
		double minHeight,
		double maxHeight,
		double meanHeight,
		int aboveSea) {

	/**
	 * Grid for the live panel. 16,384 evaluations, negligible beside a render.
	 *
	 * <p>Every proportion here is stable at this density. Plate <em>sizes</em> are
	 * not: see {@link #plateSizeIsFloored()}.
	 */
	public static final int LIVE_GRID = 128;

	/** Grid for the batch run, which can afford 160,000 evaluations and wants accuracy. */
	public static final int BATCH_GRID = 400;

	/** Folds a plate's two cell coordinates into one map key. Odd, so pairs cannot cancel. */
	private static final long PLATE_KEY_MIX = 0x9E3779B97F4A7C15L;

	/** Measures the area a view covers, at the given samples per axis. */
	public static TerrainStatistics measure(
			final TerrainModel.Snapshot world, final MapView view,
			final int seaLevel, final int grid) {
		Map<CrustType, Integer> crust = new EnumMap<>(CrustType.class);
		Map<PlateBoundaryType, Integer> boundaries = new EnumMap<>(PlateBoundaryType.class);
		Map<RegionType, Integer> regions = new EnumMap<>(RegionType.class);
		Map<Long, Integer> areaByPlate = new HashMap<>();

		double step = view.spanBlocks() / grid;
		double origin = -view.spanBlocks() * 0.5;

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0.0;
		int aboveSea = 0;

		for (int iz = 0; iz < grid; iz++) {
			for (int ix = 0; ix < grid; ix++) {
				double worldX = view.centreX() + origin + ix * step;
				double worldZ = view.centreZ() + origin + iz * step;

				var plate = world.plates().sample(worldX, worldZ);

				crust.merge(plate.crust().crustType(), 1, Integer::sum);
				boundaries.merge(plate.boundaryType(), 1, Integer::sum);
				areaByPlate.merge(
						plate.plate().cellX() * PLATE_KEY_MIX + plate.plate().cellZ(),
						1, Integer::sum);

				regions.merge(
						world.regions().sample(worldX, worldZ, plate.crust().crustType())
								.region().type(),
						1, Integer::sum);

				double height = world.terrain().heightAt(worldX, worldZ);

				min = Math.min(min, height);
				max = Math.max(max, height);
				sum += height;

				if (height > seaLevel) {
					aboveSea++;
				}
			}
		}

		// Plates clipped by the view edge understate their size, which is why this
		// reports a distribution rather than a mean those would drag down.
		double blocksPerSample = step * step;
		double[] widths = areaByPlate.values().stream()
				.mapToDouble(count -> Math.sqrt(count * blocksPerSample))
				.sorted()
				.toArray();

		int total = grid * grid;

		return new TerrainStatistics(
				total, step, crust, boundaries, regions,
				widths.length,
				widths[0], widths[widths.length / 2], widths[widths.length - 1],
				min, max, sum / total, aboveSea);
	}

	/** Share of sampled area that is plate interior rather than near a boundary. */
	public double interiorShare() {
		return boundaryCounts.getOrDefault(PlateBoundaryType.NONE, 0) / (double) samples;
	}

	/**
	 * Share of <em>real</em> boundaries of a given kind.
	 *
	 * <p>Excludes {@link PlateBoundaryType#NONE}, because most of the world is plate
	 * interior and including it made transform margins read as 89% of everything.
	 */
	public double boundaryShare(final PlateBoundaryType type) {
		int real = samples - boundaryCounts.getOrDefault(PlateBoundaryType.NONE, 0);

		return real == 0 ? 0.0 : boundaryCounts.getOrDefault(type, 0) / (double) real;
	}

	public double crustShare(final CrustType type) {
		return crustCounts.getOrDefault(type, 0) / (double) samples;
	}

	public double regionShare(final RegionType type) {
		return regionCounts.getOrDefault(type, 0) / (double) samples;
	}

	public double aboveSeaShare() {
		return aboveSea / (double) samples;
	}

	public double plateSizeRatio() {
		return smallestPlateWidth == 0.0 ? 0.0 : largestPlateWidth / smallestPlateWidth;
	}

	/**
	 * True when the smallest plate measured is at the grid's resolution limit, so
	 * both it and the ratio are quantisation artifacts rather than measurements.
	 *
	 * <p>A plate covering one sample reports a width of exactly one sample spacing
	 * however small it really is. Without this flag a coarse grid silently reports a
	 * 16x size ratio where a fine one finds 52x, and the difference looks like a
	 * regression in the generator rather than in the measurement.
	 */
	public boolean plateSizeIsFloored() {
		return smallestPlateWidth <= sampleSpacing * 1.01;
	}

	/** Fraction of the dimension's vertical range the terrain actually occupies. */
	public double dimensionUsage(final int minY, final int maxY) {
		return (maxHeight - minHeight) / (double) (maxY - minY);
	}
}
