package com.fury.terramax.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.plate.PlateBoundaryType;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateMapSettings;
import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.core.terrain.TerrainHeight;
import com.fury.terramax.core.terrain.TerrainSettings;

/**
 * Entry point for the standalone terrain simulator.
 *
 * <p>The simulator exists because the world's scale makes in-game iteration
 * impractical. Rendering the same maths to a PNG takes a fraction of a second.
 *
 * <p>It links {@code :core} only. If this class ever needs something from
 * {@code :mod}, that is a sign terrain maths has leaked into the mod and belongs
 * in {@code :core} instead.
 */
public final class SimulatorMain {
	private static final long SEED = 1L;
	private static final int IMAGE_PIXELS = 1024;

	/** Wide enough to hold roughly 8x8 plates, which is many crust cells. */
	private static final double CONTINENTAL_SPAN_CELLS = 140.0;

	/** Roughly one plate, to inspect a single boundary. */
	private static final double LOCAL_SPAN_CELLS = 24.0;

	/** Sample count for the statistics summary. Large enough for stable proportions. */
	private static final int STATS_GRID = 400;

	/**
	 * Folds a plate's two cell coordinates into one map key.
	 *
	 * <p>An odd multiplier, so distinct (x, z) pairs cannot collide by cancelling.
	 * This is a bucketing key for a statistic, not a hash anything depends on.
	 */
	private static final long PLATE_KEY_MIX = 0x9E3779B97F4A7C15L;

	/** Crust cells crossed by the section. Enough to show several boundaries in one profile. */
	private static final double CROSS_SECTION_CELLS = 100.0;

	private static final int CROSS_SECTION_WIDTH = 1600;
	private static final int CROSS_SECTION_HEIGHT = 520;

	/** The dimension's vertical range, from the design spec. */
	private static final int MIN_Y = -256;
	private static final int MAX_Y = 1792;

	private static final Path OUTPUT_DIR = Path.of("build", "renders");

	private SimulatorMain() {
	}

	public static void main(final String[] args) throws IOException {
		PlateMapSettings settings = PlateMapSettings.defaults();

		if (args.length > 0 && args[0].equals("--viewer")) {
			TerrainViewer.launch(SEED, settings);
			return;
		}

		PlateMap plates = new PlateMap(SEED, settings);

		printConfiguration(settings, plates);

		Files.createDirectories(OUTPUT_DIR);

		double spacing = settings.crustSpacingBlocks();
		MapView continental = new MapView(0, 0, spacing * CONTINENTAL_SPAN_CELLS, IMAGE_PIXELS);
		MapView local = new MapView(0, 0, spacing * LOCAL_SPAN_CELLS, IMAGE_PIXELS);

		write("plates-continental", continental, plates, MapRenderer.Layer.PLATES_WITH_EDGES);
		write("plate-type-continental", continental, plates, MapRenderer.Layer.PLATE_TYPE);
		write("boundary-type-continental", continental, plates, MapRenderer.Layer.BOUNDARY_TYPE);
		write("boundaries-continental", continental, plates, MapRenderer.Layer.BOUNDARY_DISTANCE);
		write("plates-local", local, plates, MapRenderer.Layer.PLATES_WITH_EDGES);

		TerrainHeight terrain = new TerrainHeight(SEED, plates, TerrainSettings.defaults());

		writeElevation("elevation-continental", continental, terrain, settings);
		writeElevation("elevation-local", local, terrain, settings);
		writeCrossSection(terrain, settings);

		printStatistics(plates, continental);
		printPlateSizes(plates, continental);
		printElevationStatistics(terrain, continental, settings);

		System.out.println();
		System.out.println("Wrote to " + OUTPUT_DIR.toAbsolutePath());
	}

	private static void printConfiguration(final PlateMapSettings settings, final PlateMap plates) {
		System.out.println("Terramax terrain simulator");
		System.out.printf("  seed                %d%n", SEED);
		System.out.printf("  crust spacing       %,.0f blocks%n", settings.crustSpacingBlocks());
		System.out.printf("  nuclei spacing      %,.0f blocks%n", settings.nucleiSpacingBlocks());
		System.out.printf("  nuclei max weight   %,.0f blocks (%.2fx spacing)%n",
				settings.nucleiMaxWeightBlocks(), settings.nucleiMaxWeightFactor());
		System.out.printf("  nuclei search       %dx%d cells%n",
				plates.nuclei().searchRadiusCells() * 2 + 1,
				plates.nuclei().searchRadiusCells() * 2 + 1);
		System.out.printf("  jitter              %.2f%n", settings.jitter());
		System.out.printf("  min separation      %,.0f blocks%n",
				plates.voronoi().sites().minimumSeparation());
		System.out.printf("  continental target  %.0f%%%n", settings.continentalFraction() * 100.0);
		System.out.printf("  sea level           y=%d%n", settings.seaLevel());
		System.out.printf("  continental base    y=%d +/- %d%n",
				settings.continentalBase(), settings.baseVariation());
		System.out.printf("  oceanic base        y=%d +/- %d%n",
				settings.oceanicBase(), settings.baseVariation());
		System.out.println();
	}

	/**
	 * Samples the rendered area and reports actual proportions.
	 *
	 * <p>The point is to catch a mismatch between what the settings ask for and what
	 * the maths produces, which a picture will not reveal.
	 */
	private static void printStatistics(final PlateMap plates, final MapView view) {
		Map<CrustType, Integer> byType = new EnumMap<>(CrustType.class);
		Map<PlateBoundaryType, Integer> byBoundary = new EnumMap<>(PlateBoundaryType.class);

		double step = view.spanBlocks() / STATS_GRID;
		double origin = -view.spanBlocks() * 0.5;
		int total = STATS_GRID * STATS_GRID;

		for (int iz = 0; iz < STATS_GRID; iz++) {
			for (int ix = 0; ix < STATS_GRID; ix++) {
				var sample = plates.sample(
						view.centreX() + origin + ix * step,
						view.centreZ() + origin + iz * step);

				byType.merge(sample.crust().crustType(), 1, Integer::sum);
				byBoundary.merge(sample.boundaryType(), 1, Integer::sum);
			}
		}

		System.out.println();
		System.out.printf("Sampled %,d points across the continental view:%n", total);

		for (CrustType type : CrustType.values()) {
			System.out.printf("  %-12s %5.1f%% of area%n",
					type, 100.0 * byType.getOrDefault(type, 0) / total);
		}

		// Most of the world is plate interior, so a share of total area says little
		// about the boundary mix. Report both: the interior share, then the split
		// among real boundaries, which is what compares to Earth's roughly 35/50/15.
		int interior = byBoundary.getOrDefault(PlateBoundaryType.NONE, 0);
		int boundaries = total - interior;

		System.out.printf("  %-12s %5.1f%% of area is plate interior%n",
				PlateBoundaryType.NONE, 100.0 * interior / total);

		for (PlateBoundaryType type : PlateBoundaryType.values()) {
			if (type == PlateBoundaryType.NONE) {
				continue;
			}

			System.out.printf("  %-12s %5.1f%% of real boundaries%n",
					type, boundaries == 0 ? 0.0 : 100.0 * byBoundary.getOrDefault(type, 0) / boundaries);
		}
	}

	/**
	 * Measures the actual spread of plate sizes.
	 *
	 * <p>An order-of-magnitude size range is the entire point of weighting the
	 * nuclei, and no map will tell you whether you got one: a picture of plates that
	 * all differ by 20% looks much like a picture of plates that differ by 10x.
	 *
	 * <p>Counts sampled area per plate and converts to an equivalent width, so the
	 * numbers are directly comparable with the design target of 10,000 to 100,000
	 * blocks. Plates clipped by the view edge understate their size, which is why
	 * this reports the distribution rather than a mean those would drag down.
	 */
	private static void printPlateSizes(final PlateMap plates, final MapView view) {
		Map<Long, Integer> areaByPlate = new HashMap<>();

		double step = view.spanBlocks() / STATS_GRID;
		double origin = -view.spanBlocks() * 0.5;
		double blocksPerSample = step * step;

		for (int iz = 0; iz < STATS_GRID; iz++) {
			for (int ix = 0; ix < STATS_GRID; ix++) {
				var plate = plates.sample(
						view.centreX() + origin + ix * step,
						view.centreZ() + origin + iz * step).plate();

				areaByPlate.merge(
						plate.cellX() * PLATE_KEY_MIX + plate.cellZ(), 1, Integer::sum);
			}
		}

		double[] widths = areaByPlate.values().stream()
				.mapToDouble(count -> Math.sqrt(count * blocksPerSample))
				.sorted()
				.toArray();

		System.out.println();
		System.out.printf("Plates in view: %d%n", widths.length);
		System.out.printf("  smallest        %,.0f blocks across%n", widths[0]);
		System.out.printf("  median          %,.0f blocks across%n", widths[widths.length / 2]);
		System.out.printf("  largest         %,.0f blocks across%n", widths[widths.length - 1]);
		System.out.printf("  ratio           %.1fx%n", widths[widths.length - 1] / widths[0]);
	}

	/**
	 * Plots a profile across several plates.
	 *
	 * <p>Runs diagonally rather than along an axis, so it cuts boundaries at varied
	 * angles instead of repeatedly hitting them square on.
	 */
	private static void writeCrossSection(
			final HeightField terrain, final PlateMapSettings settings) throws IOException {
		double reach = settings.crustSpacingBlocks() * CROSS_SECTION_CELLS * 0.5;

		long start = System.nanoTime();
		var image = CrossSectionPlotter.plot(
				terrain,
				-reach, -reach * 0.4,
				reach, reach * 0.4,
				MIN_Y, MAX_Y,
				settings.seaLevel(),
				CROSS_SECTION_WIDTH, CROSS_SECTION_HEIGHT);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		ImageIO.write(image, "PNG", OUTPUT_DIR.resolve("cross-section.png").toFile());

		System.out.printf("  %-26s %,10.0f blocks along, y=%d to y=%d,      %4d ms%n",
				"cross-section", reach * 2.0 * Math.hypot(1.0, 0.4), MIN_Y, MAX_Y, elapsedMs);
	}

	private static void writeElevation(
			final String name,
			final MapView view,
			final HeightField terrain,
			final PlateMapSettings settings) throws IOException {
		long start = System.nanoTime();
		var image = MapRenderer.renderElevation(terrain, view, MIN_Y, MAX_Y, settings.seaLevel());
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		ImageIO.write(image, "PNG", OUTPUT_DIR.resolve(name + ".png").toFile());

		System.out.printf("  %-26s %,10.0f blocks across, %,6.0f blocks/pixel, %4d ms%n",
				name, view.spanBlocks(), view.blocksPerPixel(), elapsedMs);
	}

	/**
	 * Reports the elevation range actually produced.
	 *
	 * <p>The point is to check the terrain uses the vertical space the dimension is
	 * paying for. A 2048-block world whose terrain spans 200 blocks is a 5.3x memory
	 * tax on nothing, and a picture will not tell you that.
	 */
	private static void printElevationStatistics(
			final HeightField terrain, final MapView view, final PlateMapSettings settings) {
		double step = view.spanBlocks() / STATS_GRID;
		double origin = -view.spanBlocks() * 0.5;

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0.0;
		int aboveSea = 0;
		int aboveThousand = 0;
		int total = STATS_GRID * STATS_GRID;

		for (int iz = 0; iz < STATS_GRID; iz++) {
			for (int ix = 0; ix < STATS_GRID; ix++) {
				double height = terrain.heightAt(
						view.centreX() + origin + ix * step,
						view.centreZ() + origin + iz * step);

				min = Math.min(min, height);
				max = Math.max(max, height);
				sum += height;

				if (height > settings.seaLevel()) {
					aboveSea++;
				}

				if (height > 1000.0) {
					aboveThousand++;
				}
			}
		}

		System.out.println();
		System.out.println("Elevation across the continental view:");
		System.out.printf("  range           y=%,.0f to y=%,.0f%n", min, max);
		System.out.printf("  mean            y=%,.0f%n", sum / total);
		System.out.printf("  above sea       %5.1f%%%n", 100.0 * aboveSea / total);
		System.out.printf("  above y=1000    %5.2f%%%n", 100.0 * aboveThousand / total);
		System.out.printf("  dimension       y=%d to y=%d, using %.0f%% of it%n",
				MIN_Y, MAX_Y, 100.0 * (max - min) / (MAX_Y - MIN_Y));
	}

	private static void write(
			final String name,
			final MapView view,
			final PlateMap plates,
			final MapRenderer.Layer layer) throws IOException {
		long start = System.nanoTime();
		var image = MapRenderer.render(plates, view, layer);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		ImageIO.write(image, "PNG", OUTPUT_DIR.resolve(name + ".png").toFile());

		System.out.printf("  %-26s %,10.0f blocks across, %,6.0f blocks/pixel, %4d ms%n",
				name, view.spanBlocks(), view.blocksPerPixel(), elapsedMs);
	}
}
