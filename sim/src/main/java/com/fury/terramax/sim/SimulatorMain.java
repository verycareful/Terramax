package com.fury.terramax.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.plate.PlateBoundaryType;
import com.fury.terramax.core.region.RegionType;

/**
 * Entry point for the standalone terrain simulator.
 *
 * <p>The simulator exists because the world's scale makes in-game iteration
 * impractical. Rendering the same maths to a PNG takes a fraction of a second.
 *
 * <p>Two modes. With {@code --viewer} it opens the interactive window. Without, it
 * writes a set of PNGs and prints measurements, which is the mode to use when
 * comparing two builds or checking a change did not break something far from where
 * you were looking.
 *
 * <p>It links {@code :core} only. If this class ever needs something from
 * {@code :mod}, that is a sign terrain maths has leaked into the mod and belongs in
 * {@code :core} instead.
 */
public final class SimulatorMain {
	private static final long SEED = 1L;
	private static final int IMAGE_PIXELS = 1024;

	/** Wide enough to hold several plates. */
	private static final double CONTINENTAL_SPAN_CELLS = 140.0;

	/** Close enough to inspect a single boundary and individual regions. */
	private static final double LOCAL_SPAN_CELLS = 24.0;

	/** Crust cells crossed by the section. Enough to show several boundaries. */
	private static final double CROSS_SECTION_CELLS = 100.0;

	/**
	 * Samples per axis when hunting for the tallest terrain.
	 *
	 * <p>Coarse on purpose. A range is thousands of blocks wide, so this only has to
	 * land somewhere on one; the close-up then shows the whole thing.
	 */
	private static final int RANGE_SEARCH_GRID = 200;

	/** Width of the range close-up, in crust cells. About one range across. */
	private static final double RANGE_SPAN_CELLS = 4.0;

	/** Columns evaluated before timing starts, so the JIT has compiled the hot path. */
	private static final int WARMUP_COLUMNS = 20_000;

	/** Chunks timed. Enough to average over both plate interiors and margins. */
	private static final int BENCH_CHUNKS = 400;

	/**
	 * Target for one chunk's surface on one thread, in milliseconds.
	 *
	 * <p>Vanilla spends a few milliseconds per chunk on terrain shape. A generator
	 * that wants to keep up with a player flying has to stay in that region once the
	 * worker pool is accounted for.
	 */
	private static final double CHUNK_BUDGET_MS = 5.0;

	/** Span showing several climate bands, so latitude is visible at all. */
	private static final double PLANETARY_SPAN_BLOCKS = 5_000_000.0;

	/** World X the climate transect walks up. */
	private static final double TRANSECT_X = 0.0;

	/** How far north the climate transect walks, covering one full pole to equator. */
	private static final double TRANSECT_SPAN_BLOCKS = 1_200_000.0;

	/** Samples along the climate transect. */
	private static final int TRANSECT_STEPS = 60;

	/** Moisture nodes solved to warm the JIT before the trajectory is timed. */
	private static final int MOISTURE_WARMUP_NODES = 200;

	/** Moisture nodes timed. Each is a full trajectory, so this need not be large. */
	private static final int MOISTURE_BENCH_NODES = 200;

	private static final int CROSS_SECTION_WIDTH = 1600;
	private static final int CROSS_SECTION_HEIGHT = 520;

	private static final Path OUTPUT_DIR = Path.of("build", "renders");

	private SimulatorMain() {
	}

	public static void main(final String[] args) throws IOException {
		if (args.length > 0 && args[0].equals("--viewer")) {
			ViewerFrame.launch(SEED);
			return;
		}

		if (args.length > 0 && args[0].equals("--climate-transect")) {
			printClimateTransect(new TerrainModel(SEED).snapshot());
			return;
		}

		TerrainModel model = new TerrainModel(SEED);
		TerrainModel.Snapshot world = model.snapshot();

		printConfiguration(model);

		Files.createDirectories(OUTPUT_DIR);

		double spacing = model.plateSettings().crustSpacingBlocks();
		MapView continental = new MapView(0, 0, spacing * CONTINENTAL_SPAN_CELLS, IMAGE_PIXELS);
		MapView local = new MapView(0, 0, spacing * LOCAL_SPAN_CELLS, IMAGE_PIXELS);

		// Wide enough to hold several climate bands. A continental view spans less
		// than one, so the latitudinal gradient is invisible at that scale however
		// correct it is.
		MapView planetary = new MapView(0, 0, PLANETARY_SPAN_BLOCKS, IMAGE_PIXELS);

		writePlates("plates-continental", continental, world, MapRenderer.Layer.PLATES_WITH_EDGES);
		writePlates("crust-type-continental", continental, world, MapRenderer.Layer.CRUST_TYPE);
		writePlates("boundary-type-continental", continental, world, MapRenderer.Layer.BOUNDARY_TYPE);
		writePlates("boundaries-continental", continental, world, MapRenderer.Layer.BOUNDARY_DISTANCE);
		writePlates("plates-local", local, world, MapRenderer.Layer.PLATES_WITH_EDGES);

		writeTerrain("elevation-continental", continental, world,
				MapRenderer.TerrainLayer.ELEVATION_HYPSOMETRIC);
		writeTerrain("elevation-local", local, world,
				MapRenderer.TerrainLayer.ELEVATION_HYPSOMETRIC);
		writeTerrain("elevation-magma-continental", continental, world,
				MapRenderer.TerrainLayer.ELEVATION_MAGMA);
		writeTerrain("elevation-raw-continental", continental, world,
				MapRenderer.TerrainLayer.ELEVATION_RAW);
		writeTerrain("elevation-raw-local", local, world,
				MapRenderer.TerrainLayer.ELEVATION_RAW);
		writeTerrain("region-type-continental", continental, world,
				MapRenderer.TerrainLayer.REGION_TYPE);
		writeTerrain("region-type-local", local, world,
				MapRenderer.TerrainLayer.REGION_TYPE);
		writeTerrain("region-id-local", local, world,
				MapRenderer.TerrainLayer.REGION_ID);
		writeTerrain("temperature-continental", continental, world,
				MapRenderer.TerrainLayer.TEMPERATURE);
		writeTerrain("life-zone-continental", continental, world,
				MapRenderer.TerrainLayer.LIFE_ZONE);
		writeTerrain("wind-continental", continental, world,
				MapRenderer.TerrainLayer.WIND);
		writeTerrain("wind-local", local, world,
				MapRenderer.TerrainLayer.WIND);
		writeTerrain("wind-planetary", planetary, world,
				MapRenderer.TerrainLayer.WIND);
		writeTerrain("life-zone-planetary", planetary, world,
				MapRenderer.TerrainLayer.LIFE_ZONE);

		writeTerrain("precipitation-continental", continental, world,
				MapRenderer.TerrainLayer.PRECIPITATION);
		writeTerrain("precipitation-local", local, world,
				MapRenderer.TerrainLayer.PRECIPITATION);
		writeTerrain("humidity-continental", continental, world,
				MapRenderer.TerrainLayer.HUMIDITY);
		writeTerrain("humidity-local", local, world,
				MapRenderer.TerrainLayer.HUMIDITY);
		writeTerrain("foehn-continental", continental, world,
				MapRenderer.TerrainLayer.FOEHN_WARMING);
		writeTerrain("precipitation-planetary", planetary, world,
				MapRenderer.TerrainLayer.PRECIPITATION);

		writeRangeDetail(world, spacing);
		writeCrossSection(world, spacing);

		printStatistics(TerrainStatistics.measure(
				world, continental, MapPanel.SEA_LEVEL, TerrainStatistics.BATCH_GRID));
		printChunkCost(world);
		printMoistureCost(world);

		System.out.println();
		System.out.println("Wrote to " + OUTPUT_DIR.toAbsolutePath());
	}

	private static void printConfiguration(final TerrainModel model) {
		var plates = model.plateSettings();
		var regions = model.regionSettings();

		System.out.println("Terramax terrain simulator");
		System.out.printf("  seed                %d%n", model.seed());
		System.out.printf("  crust spacing       %,.0f blocks%n", plates.crustSpacingBlocks());
		System.out.printf("  nuclei spacing      %,.0f blocks%n", plates.nucleiSpacingBlocks());
		System.out.printf("  nuclei max weight   %,.0f blocks (%.2fx spacing)%n",
				plates.nucleiMaxWeightBlocks(), plates.nucleiMaxWeightFactor());
		System.out.printf("  nuclei search       %dx%d cells%n",
				model.snapshot().plates().nuclei().searchRadiusCells() * 2 + 1,
				model.snapshot().plates().nuclei().searchRadiusCells() * 2 + 1);
		System.out.printf("  region spacing      %,.0f blocks%n", regions.spacingBlocks());
		System.out.printf("  continental target  %.0f%%%n", plates.continentalFraction() * 100.0);
		System.out.printf("  sea level           y=%d%n", plates.seaLevel());
		System.out.printf("  crust base          continent y=%d, ocean y=%d, +/- %d%n",
				plates.continentalBase(), plates.oceanicBase(), plates.baseVariation());
		System.out.println();
	}

	/**
	 * Reports what the terrain actually measures.
	 *
	 * <p>The point is to catch a mismatch between what the settings ask for and what
	 * the maths produces, which a picture will not reveal. Three defects in this
	 * project were invisible on the maps and immediately obvious here.
	 */
	private static void printStatistics(final TerrainStatistics s) {
		System.out.println();
		System.out.printf("Measured over %,d samples of the continental view:%n", s.samples());

		System.out.println();

		for (CrustType type : CrustType.values()) {
			System.out.printf("  %-12s %5.1f%% of area%n", type, s.crustShare(type) * 100.0);
		}

		System.out.println();
		System.out.printf("  %-12s %5.1f%% of area is plate interior%n",
				PlateBoundaryType.NONE, s.interiorShare() * 100.0);

		for (PlateBoundaryType type : PlateBoundaryType.values()) {
			if (type != PlateBoundaryType.NONE) {
				System.out.printf("  %-12s %5.1f%% of real boundaries%n",
						type, s.boundaryShare(type) * 100.0);
			}
		}

		System.out.println();
		System.out.printf("Plates in view: %d%n", s.plateCount());
		System.out.printf("  smallest        %,.0f blocks across%n", s.smallestPlateWidth());
		System.out.printf("  median          %,.0f blocks across%n", s.medianPlateWidth());
		System.out.printf("  largest         %,.0f blocks across%n", s.largestPlateWidth());
		System.out.printf("  ratio           %.1fx%n", s.plateSizeRatio());

		if (s.plateSizeIsFloored()) {
			System.out.printf("  (smallest is at the %,.0f-block sample spacing, so it and the"
					+ " ratio are grid artifacts)%n", s.sampleSpacing());
		}

		System.out.println();
		System.out.println("Regions by share of area:");

		for (RegionType type : RegionType.values()) {
			System.out.printf("  %-16s %5.1f%%%n", type, s.regionShare(type) * 100.0);
		}

		System.out.println();
		System.out.println("Elevation:");
		System.out.printf("  range           y=%,.0f to y=%,.0f%n", s.minHeight(), s.maxHeight());
		System.out.printf("  mean            y=%,.0f%n", s.meanHeight());
		System.out.printf("  above sea       %5.1f%%%n", s.aboveSeaShare() * 100.0);
		System.out.printf("  dimension       y=%d to y=%d, using %.0f%% of it%n",
				MapPanel.MIN_Y, MapPanel.MAX_Y,
				s.dimensionUsage(MapPanel.MIN_Y, MapPanel.MAX_Y) * 100.0);

		System.out.println();
		System.out.printf("Moisture, over %d samples:%n",
				TerrainStatistics.MOISTURE_GRID * TerrainStatistics.MOISTURE_GRID);
		System.out.printf("  rain            %.3f to %.3f, mean %.3f%n",
				s.minPrecipitation(), s.maxPrecipitation(), s.meanPrecipitation());
		System.out.printf("  humidity        %5.1f%%%n", s.meanHumidity() * 100.0);

		if (s.minHeight() < MapPanel.MIN_Y || s.maxHeight() > MapPanel.MAX_Y) {
			System.out.println();
			System.out.println("  *** OUT OF BOUNDS: terrain leaves the dimension and will be clipped");
		}
	}

	/**
	 * Times terrain generation at the rate Minecraft will actually ask for it.
	 *
	 * <p>Every other measurement here is about whether the world looks right. This one
	 * is about whether it can exist. A chunk is 256 columns, and the plate lookup
	 * feeding each one now resolves the plate of up to 25 candidate crust cells, each
	 * of which is an 81-site weighted nuclei search. That is a large constant on the
	 * hottest path in the generator, and the simulator's own render times hide it
	 * because they are spread across every core.
	 *
	 * <p>Single-threaded on purpose. Minecraft generates chunks on a worker pool, so
	 * the number that matters is the cost of one chunk on one thread, not the
	 * throughput of a machine with twelve.
	 *
	 * <p>Sampled away from the origin so it does not accidentally measure only plate
	 * interior, which is the cheap case: interiors exhaust the candidate search
	 * without finding a differing plate, margins usually stop early.
	 */
	private static void printChunkCost(final TerrainModel.Snapshot world) {
		double[] origins = {0.0, 120_000.0, -348_600.0, 75_600.0};

		// Warm up first. The first few thousand calls run interpreted, and reporting
		// those as the cost would overstate it several times over.
		for (int i = 0; i < WARMUP_COLUMNS; i++) {
			world.terrain().heightAt(i * 7.0, i * 13.0);
		}

		long start = System.nanoTime();
		int columns = 0;

		for (int chunk = 0; chunk < BENCH_CHUNKS; chunk++) {
			double baseX = origins[chunk % 2 * 2] + (chunk / 2) * 16.0;
			double baseZ = origins[chunk % 2 * 2 + 1] + (chunk / 2) * 16.0;

			for (int cz = 0; cz < 16; cz++) {
				for (int cx = 0; cx < 16; cx++) {
					world.terrain().heightAt(baseX + cx, baseZ + cz);
					columns++;
				}
			}
		}

		double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
		double perChunk = elapsedMs / BENCH_CHUNKS;

		System.out.println();
		System.out.println("Generation cost, single-threaded:");
		System.out.printf("  %,d columns over %d chunks in %,.0f ms%n",
				columns, BENCH_CHUNKS, elapsedMs);
		System.out.printf("  %.2f ms per chunk surface%n", perChunk);
		System.out.printf("  %,.0f columns per second%n", columns / (elapsedMs / 1000.0));

		// A rough budget. Vanilla spends a few ms per chunk on terrain shape, and a
		// generator wanting to keep up with a player flying needs to stay in that
		// region across the whole worker pool.
		if (perChunk > CHUNK_BUDGET_MS) {
			System.out.printf("  *** OVER BUDGET: %.2f ms against a %.0f ms target%n",
					perChunk, CHUNK_BUDGET_MS);
		}
	}

	/**
	 * Finds the tallest terrain in the continental view and renders a close-up of it.
	 *
	 * <p>Most of the world is plate interior, so a view dropped at the origin usually
	 * contains no mountains at all and says nothing about whether the range machinery
	 * works. Hunting for one by panning is not a workflow. This goes and finds one.
	 *
	 * <p>Also prints a section straight across it, since a top-down view cannot show
	 * whether the ridges have valleys between them or are merely shaded to look as
	 * though they do.
	 */
	/**
	 * Times one moisture trajectory, the dearest thing in the generator.
	 *
	 * <p>Reported per node and per chunk, because those are wildly different numbers
	 * and only the second one decides whether this ships. A node is expensive; the
	 * lattice means a chunk pays a thousandth of one.
	 */
	/**
	 * Walks north from the equator printing the climate at each step.
	 *
	 * <p>Exists because a colour ramp cannot answer "is that edge real". A band
	 * boundary that looks like a hard step on the map is either a discontinuity in
	 * the model or a discontinuity in the palette, and only numbers separate the two.
	 */
	private static void printClimateTransect(final TerrainModel.Snapshot world) {
		var moisture = world.moisture().canonical();
		double step = world.temperature().latitude(0.0) >= 0.0
				? TRANSECT_SPAN_BLOCKS / TRANSECT_STEPS
				: 0.0;

		System.out.printf("%12s %6s %8s %8s %8s %8s %8s %8s%n",
				"z", "lat", "wind", "conv", "y", "rain", "rh", "foehn");

		for (int i = 0; i <= TRANSECT_STEPS; i++) {
			double worldZ = i * step;
			double latitude = world.temperature().latitude(worldZ);
			var flow = world.wind().at(TRANSECT_X, worldZ, latitude);
			var air = moisture.solve(TRANSECT_X, worldZ);

			double separation = 12_000.0;
			double convergence = -(world.wind().baseFlow(
							worldZ + separation, world.temperature().latitude(worldZ + separation))
									.southward()
					- world.wind().baseFlow(
							worldZ - separation, world.temperature().latitude(worldZ - separation))
									.southward()) / (2.0 * separation);

			System.out.printf("%,12.0f %6.3f %8.3f %8.2e %8.0f %8.4f %7.0f%% %8.2f%n",
					worldZ, latitude, flow.speed(), convergence,
					world.terrain().heightAt(TRANSECT_X, worldZ),
					air.precipitation(), air.humidity() * 100.0, air.foehnWarming());
		}
	}

	private static void printMoistureCost(final TerrainModel.Snapshot world) {
		var moisture = world.moisture().canonical();
		double spacing = moisture.settings().latticeSpacingBlocks();

		// Off in a corner nothing else has touched, so the cache is cold and the
		// measurement is of the trace rather than of a hash lookup.
		double origin = 3_000_000.0;

		for (int i = 0; i < MOISTURE_WARMUP_NODES; i++) {
			moisture.solve(origin + i * spacing, origin);
		}

		long start = System.nanoTime();

		for (int i = 0; i < MOISTURE_BENCH_NODES; i++) {
			moisture.solve(origin - i * spacing, origin + spacing);
		}

		double perNodeMs = (System.nanoTime() - start) / 1e6 / MOISTURE_BENCH_NODES;

		// A 16 by 16 chunk against a lattice cell, times the four nodes a bilinear
		// read touches. Nodes are shared with neighbouring chunks, so this is an
		// upper bound rather than an average.
		double nodesPerChunk = 4.0 * (16.0 * 16.0) / (spacing * spacing);

		System.out.println();
		System.out.println("MOISTURE COST");
		System.out.printf("  fetch traced                 %,10.0f blocks upwind%n",
				moisture.settings().fetchBlocks());
		System.out.printf("  one node                     %10.2f ms%n", perNodeMs);
		System.out.printf("  amortised per chunk          %10.4f ms%n",
				perNodeMs * nodesPerChunk);
	}

	private static void writeRangeDetail(
			final TerrainModel.Snapshot world, final double spacing) throws IOException {
		double span = spacing * CONTINENTAL_SPAN_CELLS;
		double step = span / RANGE_SEARCH_GRID;
		double origin = -span * 0.5;

		double bestX = 0.0;
		double bestZ = 0.0;
		double best = -Double.MAX_VALUE;

		for (int iz = 0; iz < RANGE_SEARCH_GRID; iz++) {
			for (int ix = 0; ix < RANGE_SEARCH_GRID; ix++) {
				double worldX = origin + ix * step;
				double worldZ = origin + iz * step;
				double height = world.terrain().heightAt(worldX, worldZ);

				if (height > best) {
					best = height;
					bestX = worldX;
					bestZ = worldZ;
				}
			}
		}

		MapView view = new MapView(bestX, bestZ, spacing * RANGE_SPAN_CELLS, IMAGE_PIXELS);

		System.out.println();
		System.out.printf("Tallest terrain found at %,.0f, %,.0f at y=%,.0f%n", bestX, bestZ, best);

		writeTerrain("range-raw", view, world, MapRenderer.TerrainLayer.ELEVATION_RAW);
		writeTerrain("range-elevation", view, world,
				MapRenderer.TerrainLayer.ELEVATION_HYPSOMETRIC);

		// Wind at range scale is the only view where deflection is legible. The whole
		// view sits inside one climate band, so the base flow is near-constant and
		// every variation on screen is terrain bending the air.
		writeTerrain("range-wind", view, world, MapRenderer.TerrainLayer.WIND);
		writeTerrain("range-life-zone", view, world, MapRenderer.TerrainLayer.LIFE_ZONE);

		// Rain shadow is only visible where one range fills the frame. At continental
		// scale a range is four pixels wide and its lee is one.
		writeTerrain("range-precipitation", view, world,
				MapRenderer.TerrainLayer.PRECIPITATION);
		writeTerrain("range-foehn", view, world, MapRenderer.TerrainLayer.FOEHN_WARMING);

		double reach = spacing * RANGE_SPAN_CELLS * 0.5;
		var section = CrossSectionPlotter.plot(
				world.terrain(),
				bestX - reach, bestZ - reach * 0.3,
				bestX + reach, bestZ + reach * 0.3,
				MapPanel.MIN_Y, MapPanel.MAX_Y, MapPanel.SEA_LEVEL,
				CROSS_SECTION_WIDTH, CROSS_SECTION_HEIGHT);

		ImageIO.write(section, "PNG", OUTPUT_DIR.resolve("range-section.png").toFile());
		System.out.printf("  %-30s %,10.0f blocks along%n", "range-section", reach * 2.0);
	}

	/**
	 * Plots a profile across several plates.
	 *
	 * <p>Runs diagonally rather than along an axis, so it cuts boundaries at varied
	 * angles instead of repeatedly hitting them square on.
	 */
	private static void writeCrossSection(
			final TerrainModel.Snapshot world, final double spacing) throws IOException {
		double reach = spacing * CROSS_SECTION_CELLS * 0.5;

		long start = System.nanoTime();
		var image = CrossSectionPlotter.plot(
				world.terrain(),
				-reach, -reach * 0.4,
				reach, reach * 0.4,
				MapPanel.MIN_Y, MapPanel.MAX_Y, MapPanel.SEA_LEVEL,
				CROSS_SECTION_WIDTH, CROSS_SECTION_HEIGHT);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		ImageIO.write(image, "PNG", OUTPUT_DIR.resolve("cross-section.png").toFile());

		System.out.printf("  %-30s %,10.0f blocks along,                        %5d ms%n",
				"cross-section", reach * 2.0 * Math.hypot(1.0, 0.4), elapsedMs);
	}

	private static void writePlates(
			final String name, final MapView view,
			final TerrainModel.Snapshot world, final MapRenderer.Layer layer) throws IOException {
		write(name, view, MapRenderer.render(world.plates(), view, layer));
	}

	private static void writeTerrain(
			final String name, final MapView view,
			final TerrainModel.Snapshot world,
			final MapRenderer.TerrainLayer layer) throws IOException {
		write(name, view, MapRenderer.renderTerrain(
				world, view, layer, MapPanel.MIN_Y, MapPanel.MAX_Y, MapPanel.SEA_LEVEL));
	}

	private static void write(
			final String name, final MapView view,
			final java.awt.image.BufferedImage image) throws IOException {
		ImageIO.write(image, "PNG", OUTPUT_DIR.resolve(name + ".png").toFile());

		System.out.printf("  %-30s %,10.0f blocks across, %,8.2f blocks/pixel%n",
				name, view.spanBlocks(), view.blocksPerPixel());
	}
}
