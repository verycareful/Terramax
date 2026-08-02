package com.fury.terramax.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.fury.terramax.core.plate.PlateBoundaryType;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateMapSettings;
import com.fury.terramax.core.plate.PlateType;
import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.core.terrain.TerrainHeight;
import com.fury.terramax.core.terrain.TerrainSettings;

/**
 * Entry point for the standalone terrain simulator.
 *
 * <p>The simulator exists because Terramax's scale makes in-game iteration
 * impractical: with plate centres roughly 100,000 blocks apart, reaching a plate
 * boundary means flying 50,000 blocks. Rendering the same maths to a PNG takes a
 * fraction of a second.
 *
 * <p>It links {@code :core} only. If this class ever needs something from
 * {@code :mod}, that is a sign terrain maths has leaked into the mod and belongs
 * in {@code :core} instead.
 */
public final class SimulatorMain {
	private static final long SEED = 1L;
	private static final int IMAGE_PIXELS = 1024;

	/** Wide enough to hold roughly 8x8 plates. */
	private static final double CONTINENTAL_SPAN_PLATES = 8.0;

	/** Roughly one plate, to inspect a single boundary. */
	private static final double LOCAL_SPAN_PLATES = 1.5;

	/** Sample count for the statistics summary. Large enough for stable proportions. */
	private static final int STATS_GRID = 400;

	/** Plates crossed by the section. Enough to show several boundaries in one profile. */
	private static final double CROSS_SECTION_PLATES = 6.0;

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

		double spacing = settings.spacingBlocks();
		MapView continental = new MapView(0, 0, spacing * CONTINENTAL_SPAN_PLATES, IMAGE_PIXELS);
		MapView local = new MapView(0, 0, spacing * LOCAL_SPAN_PLATES, IMAGE_PIXELS);

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
		printElevationStatistics(terrain, continental, settings);

		System.out.println();
		System.out.println("Wrote to " + OUTPUT_DIR.toAbsolutePath());
	}

	private static void printConfiguration(final PlateMapSettings settings, final PlateMap plates) {
		System.out.println("Terramax terrain simulator");
		System.out.printf("  seed                %d%n", SEED);
		System.out.printf("  plate spacing       %,.0f blocks%n", settings.spacingBlocks());
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
		Map<PlateType, Integer> byType = new EnumMap<>(PlateType.class);
		Map<PlateBoundaryType, Integer> byBoundary = new EnumMap<>(PlateBoundaryType.class);

		double step = view.spanBlocks() / STATS_GRID;
		double origin = -view.spanBlocks() * 0.5;
		int total = STATS_GRID * STATS_GRID;

		for (int iz = 0; iz < STATS_GRID; iz++) {
			for (int ix = 0; ix < STATS_GRID; ix++) {
				var sample = plates.sample(
						view.centreX() + origin + ix * step,
						view.centreZ() + origin + iz * step);

				byType.merge(sample.plate().type(), 1, Integer::sum);
				byBoundary.merge(sample.boundaryType(), 1, Integer::sum);
			}
		}

		System.out.println();
		System.out.printf("Sampled %,d points across the continental view:%n", total);

		for (PlateType type : PlateType.values()) {
			System.out.printf("  %-12s %5.1f%% of area%n",
					type, 100.0 * byType.getOrDefault(type, 0) / total);
		}

		for (PlateBoundaryType type : PlateBoundaryType.values()) {
			System.out.printf("  %-12s %5.1f%% of boundaries by nearest%n",
					type, 100.0 * byBoundary.getOrDefault(type, 0) / total);
		}
	}

	/**
	 * Plots a profile across several plates.
	 *
	 * <p>Runs diagonally rather than along an axis, so it cuts boundaries at varied
	 * angles instead of repeatedly hitting them square on.
	 */
	private static void writeCrossSection(
			final HeightField terrain, final PlateMapSettings settings) throws IOException {
		double reach = settings.spacingBlocks() * CROSS_SECTION_PLATES * 0.5;

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
