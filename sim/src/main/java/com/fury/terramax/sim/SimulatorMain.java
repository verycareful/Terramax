package com.fury.terramax.sim;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.fury.terramax.core.climate.MoistureField;
import com.fury.terramax.core.fluvial.BasinIndex;
import com.fury.terramax.core.fluvial.BasinNetwork;
import com.fury.terramax.core.fluvial.CreekTrees;
import com.fury.terramax.core.fluvial.DrainageSettings;
import com.fury.terramax.core.fluvial.FlowLattice;
import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.core.terrain.UpliftHeight;
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

	/**
	 * Samples per side when measuring basins.
	 *
	 * <p>Coarser than the terrain grid on purpose. A basin is tens of thousands of
	 * blocks across, so 200 samples over a continental view resolves them comfortably,
	 * and each sample costs a province tile lookup rather than a noise call.
	 */
	private static final int BASIN_SAMPLE_GRID = 200;

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

		if (args.length > 0 && args[0].equals("--drainage-probe")) {
			printDrainageProbe(new TerrainModel(SEED).snapshot());
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
		writeTerrain("basins-continental", continental, world,
				MapRenderer.TerrainLayer.BASIN_ID);
		writeTerrain("basins-planetary", planetary, world,
				MapRenderer.TerrainLayer.BASIN_ID);
		writeTerrain("drainage-local", local, world,
				MapRenderer.TerrainLayer.DRAINAGE);
		writeTerrain("discharge-local", local, world,
				MapRenderer.TerrainLayer.DISCHARGE);
		writeTerrain("hillslope-local", local, world,
				MapRenderer.TerrainLayer.HILLSLOPE);
		writeTerrain("lakes-local", local, world,
				MapRenderer.TerrainLayer.LAKES);
		writeTerrain("lakes-continental", continental, world,
				MapRenderer.TerrainLayer.LAKES);
		writeTerrain("incision-local", local, world,
				MapRenderer.TerrainLayer.INCISION);

		writeRangeDetail(world, spacing);
		writeCrossSection(world, spacing);

		printStatistics(TerrainStatistics.measure(
				world, continental, MapPanel.SEA_LEVEL, TerrainStatistics.BATCH_GRID));
		printChunkCost(world);
		printMoistureCost(world);
		printBasinStatistics(world, continental);
		printDrainageCost(world);

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
					world.coarse().heightAt(TRANSECT_X, worldZ),
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
				double height = world.coarse().heightAt(worldX, worldZ);

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
		writeTerrain("range-incision", view, world, MapRenderer.TerrainLayer.INCISION);
		writeTerrain("range-drainage", view, world, MapRenderer.TerrainLayer.DRAINAGE);
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

	/**
	 * What a drainage lookup costs per chunk.
	 *
	 * <p>Measured before the carve is built on top of it, not after. The three tier
	 * solves amortise to nothing across the millions of chunks a basin covers, but the
	 * nearest-channel search runs for every column of every chunk and is the number that
	 * decides whether this subsystem is affordable.
	 *
	 * <p>Warmed first. A cold measurement here would be measuring basin construction and
	 * JIT compilation rather than the query, which is the thing being asked about.
	 */
	private static void printDrainageCost(final TerrainModel.Snapshot world) {
		int side = 16;
		int chunks = 64;
		double baseX = 120_000.0;
		double baseZ = -330_000.0;

		for (int i = 0; i < 4_096; i++) {
			world.drainage().sample(baseX + (i % 64) * 4.0, baseZ + (i / 64) * 4.0);
		}

		long started = System.nanoTime();
		int columns = 0;

		for (int chunk = 0; chunk < chunks; chunk++) {
			double chunkX = baseX + (chunk % 8) * 16.0;
			double chunkZ = baseZ + (chunk / 8) * 16.0;

			for (int cz = 0; cz < side; cz++) {
				for (int cx = 0; cx < side; cx++) {
					world.drainage().sample(chunkX + cx, chunkZ + cz);
					columns++;
				}
			}
		}

		double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;

		System.out.println();
		System.out.println("DRAINAGE COST");
		System.out.printf("  %,d columns over %d chunks in %,.0f ms%n", columns, chunks, elapsedMs);
		System.out.printf("  %.3f ms per chunk from drainage alone%n", elapsedMs / chunks);
		System.out.printf("  basins solved %,d, creek patches %,d%n",
				world.drainage().solvedBasins(), world.drainage().creeks().cachedPatches());
		printInversionClamps(world);
	}

	/**
	 * How often the carve had to be clamped to stop it inverting.
	 *
	 * <p>Should be zero. Tier 2 channel elevations come from the filled uplift surface
	 * and tier 3 elevations climb toward the budget, so a channel standing above its own
	 * budget should be unreachable. Counting it anyway matters because a guard that fires
	 * often is not a guard doing its job, it is a model that is wrong somewhere, and
	 * without a number nobody would ever find out.
	 */
	private static void printInversionClamps(final TerrainModel.Snapshot world) {
		long clamps = world.terrain().inversionClamps() + world.coarse().inversionClamps();

		System.out.printf("  channel above column %,d, mean %.2f blocks, worst %.1f   [steep ground, not a defect]%n",
				clamps,
				Math.max(world.terrain().meanInversionExcess(),
						world.coarse().meanInversionExcess()),
				Math.max(world.terrain().worstInversionExcess(),
						world.coarse().worstInversionExcess()));
	}

	/**
	 * Basin count and, more importantly, the largest basin found.
	 *
	 * <p><b>The largest figure is the margin assumption under measurement.</b> Basins
	 * are keyed by outlet so that two province tiles containing the same straddling
	 * basin agree by construction, and that holds only while the 256,000-block margin
	 * exceeds the largest basin. A figure approaching the margin means the bound is not
	 * safe on this seed and the design has to widen it.
	 */
	private static void printBasinStatistics(
			final TerrainModel.Snapshot world, final MapView view) {
		BasinIndex basins = world.basins();
		HeightField terrain = world.coarse();

		Map<Long, double[]> extents = new HashMap<>();
		int land = 0;

		double step = view.spanBlocks() / BASIN_SAMPLE_GRID;
		double minX = view.centreX() - view.spanBlocks() * 0.5;
		double minZ = view.centreZ() - view.spanBlocks() * 0.5;

		for (int iz = 0; iz < BASIN_SAMPLE_GRID; iz++) {
			for (int ix = 0; ix < BASIN_SAMPLE_GRID; ix++) {
				double worldX = minX + (ix + 0.5) * step;
				double worldZ = minZ + (iz + 0.5) * step;

				if (terrain.heightAt(worldX, worldZ) <= MapPanel.SEA_LEVEL) {
					continue;
				}

				land++;

				// minX, minZ, maxX, maxZ, count
				double[] box = extents.computeIfAbsent(
						basins.outletAt(worldX, worldZ),
						key -> new double[] {worldX, worldZ, worldX, worldZ, 0.0});

				box[0] = Math.min(box[0], worldX);
				box[1] = Math.min(box[1], worldZ);
				box[2] = Math.max(box[2], worldX);
				box[3] = Math.max(box[3], worldZ);
				box[4]++;
			}
		}

		double largest = 0.0;
		double largestArea = 0.0;

		for (double[] box : extents.values()) {
			largest = Math.max(largest, Math.max(box[2] - box[0], box[3] - box[1]));
			largestArea = Math.max(largestArea, box[4]);
		}

		double marginBlocks = DrainageSettings.defaults().provinceMarginBlocks();

		// Water statistics over the same samples, so lakes are reported against many
		// basins rather than whichever one the probe happened to pick.
		int lake = 0;
		int terminal = 0;
		int playa = 0;
		int endorheic = 0;

		for (int iz = 0; iz < BASIN_SAMPLE_GRID; iz++) {
			for (int ix = 0; ix < BASIN_SAMPLE_GRID; ix++) {
				double worldX = minX + (ix + 0.5) * step;
				double worldZ = minZ + (iz + 0.5) * step;
				double height = terrain.heightAt(worldX, worldZ);

				if (height <= MapPanel.SEA_LEVEL) {
					continue;
				}

				var drain = world.drainage().sample(worldX, worldZ);

				if (drain.endorheic()) {
					endorheic++;
				}

				if (drain.lakeSurface() > height) {
					lake++;

					if (world.drainage().terminalLakeAt(worldX, worldZ)) {
						terminal++;
					}
				} else if (world.drainage().playaAt(worldX, worldZ)) {
					playa++;
				}
			}
		}

		System.out.println();
		System.out.println("BASINS, over " + land + " land samples");
		System.out.printf("  distinct basins        %,d%n", extents.size());
		System.out.printf("  largest span         %,.0f blocks   [needs %,.0f margin, has %,.0f]%n",
				largest, largest * 1.5, marginBlocks);
		System.out.printf("  largest share          %.1f%% of land in view%n",
				land == 0 ? 0.0 : 100.0 * largestArea / land);

		// Not compared against Earth's 2 percent. That figure is dominated by glacial
		// lakes, and this world has no ice history yet: the design holds MORAINE and
		// LAKE_LAND back until the glacial overprint exists. Earth's non-glacial lakes
		// are closer to half a percent of land.
		System.out.printf("  lakes                  %.2f%% of land, %.0f%% of them terminal"
				+ "   [non-glacial Earth about 0.5%%]%n",
				100.0 * lake / land, lake == 0 ? 0.0 : 100.0 * terminal / lake);
		System.out.printf("  playas                 %.2f%% of land   [Earth about 0.3%%]%n",
				100.0 * playa / land);
		System.out.printf("  endorheic              %.1f%% of land drains to no sea   [Earth about 18%%]%n",
				100.0 * endorheic / land);

		// A basin straddling a tile edge sits half in each tile, so half its span has
		// to clear both extents, with room to spare for the divides around it. The
		// bound is therefore 1.5 times the span, not the span itself.
		if (largest * 1.5 > marginBlocks) {
			System.out.printf("  *** largest basin needs %,.0f blocks of margin and has "
					+ "%,.0f; two tiles could disagree about it%n", largest * 1.5, marginBlocks);
		}
	}

	/**
	 * Measures one province tile directly, without rendering anything.
	 *
	 * <p>Exists because a full batch takes five minutes and a routing question can be
	 * answered in seconds. It reports the two things that decide whether a routing tier
	 * is sound: how much of the surface ends up submerged under its own fill, and how
	 * rough the surface looks at the spacing the tier samples it.
	 *
	 * <p><b>The roughness comparison is the important one.</b> If neighbouring samples
	 * at the tier's own spacing differ by as much as samples taken a tenth of that
	 * apart, the tier is not seeing terrain, it is seeing aliased noise, and routing
	 * over noise gives incoherent basins no matter how correct the algorithm is.
	 */
	private static void printDrainageProbe(final TerrainModel.Snapshot world) {
		DrainageSettings settings = DrainageSettings.defaults();
		UpliftHeight uplift = world.uplift();

		int extent = settings.provinceExtentCells();
		double spacing = settings.provinceLatticeBlocks();

		System.out.println("DRAINAGE PROBE, one province tile at the origin");
		System.out.printf("  lattice              %,.0f blocks, %d x %d = %,d nodes%n",
				spacing, extent, extent, extent * extent);

		long started = System.nanoTime();

		FlowLattice flow = new FlowLattice(extent, extent, spacing,
				-settings.provinceMarginBlocks(), -settings.provinceMarginBlocks());
		flow.sampleSurface(uplift.tectonic());

		long sampled = System.nanoTime();

		flow.floodFill(settings.baseLevelY());
		flow.route();

		long routed = System.nanoTime();

		int land = 0;
		int submerged = 0;
		double deepest = 0.0;
		double totalDepth = 0.0;

		for (int i = 0; i < flow.size(); i++) {
			if (flow.surface(i) <= settings.baseLevelY()) {
				continue;
			}

			land++;
			double depth = flow.filled(i) - flow.surface(i);

			if (depth > 0.0) {
				submerged++;
				totalDepth += depth;
				deepest = Math.max(deepest, depth);
			}
		}

		System.out.printf("  sample surface       %,d ms%n", (sampled - started) / 1_000_000);
		System.out.printf("  flood and route      %,d ms%n", (routed - sampled) / 1_000_000);
		System.out.printf("  land nodes           %,d of %,d%n", land, flow.size());
		System.out.printf("  submerged by fill    %.1f%% of land, deepest %,.0f blocks, mean %,.0f%n",
				land == 0 ? 0.0 : 100.0 * submerged / land, deepest,
				submerged == 0 ? 0.0 : totalDepth / submerged);

		printRoughness(uplift, spacing);
		printRoughness(uplift, settings.basinLatticeBlocks());
		printRoughness(uplift, 250.0);

		printWaterBalance(world);

		System.out.println();
		System.out.println("  routing tectonics (what tier 1 actually uses):");
		printBasinSizes(flow, settings);

		// The same tile routed on tectonics alone. Region relief has a 2,300-block
		// wavelength and this tier samples at 8,000, so the question is whether that
		// term is carrying information here or just noise.
		FlowLattice bare = new FlowLattice(extent, extent, spacing,
				-settings.provinceMarginBlocks(), -settings.provinceMarginBlocks());
		bare.sampleSurface(uplift.tectonic());
		bare.floodFill(settings.baseLevelY());
		bare.route();

		System.out.println();
		System.out.println("  routing tectonics alone (no region relief):");
		printBasinSizes(bare, settings);
		printRoughness2(uplift.tectonic(), spacing);
		printRoughness2(uplift.tectonic(), settings.basinLatticeBlocks());

		printBasinNetwork(world, settings);
	}

	/**
	 * Builds tier 2 for the largest basin in reach and reports what came out.
	 *
	 * <p>The monotonicity line is the one that matters. Everything else describes how
	 * the network looks; that line says whether it is a network at all.
	 */
	private static void printBasinNetwork(
			final TerrainModel.Snapshot world, final DrainageSettings settings) {
		BasinIndex basins = world.basins();
		HeightField terrain = world.coarse();

		long best = 0L;
		int bestCells = -1;
		boolean any = false;

		// Selected by area, not by bounding box. An elongated coastal basin can have a
		// box spanning 144,000 blocks and almost no land inside it, which is exactly
		// how a 14-cell river came to be reported as the largest basin in reach.
		for (int iz = -20; iz <= 20; iz++) {
			for (int ix = -20; ix <= 20; ix++) {
				double worldX = ix * 12_000.0;
				double worldZ = iz * 12_000.0;

				if (terrain.heightAt(worldX, worldZ) <= MapPanel.SEA_LEVEL) {
					continue;
				}

				long outlet = basins.outletAt(worldX, worldZ);
				int cells = basins.cellCountOf(outlet);

				if (cells > bestCells) {
					bestCells = cells;
					best = outlet;
					any = true;
				}
			}
		}

		double[] bestBounds = basins.boundsOf(best);
		double bestSpan = Math.max(bestBounds[2] - bestBounds[0], bestBounds[3] - bestBounds[1]);

		if (!any) {
			System.out.println("  no land found for a basin network");
			return;
		}

		System.out.println();
		System.out.printf("  TIER 2, largest basin in reach: %,d province cells, "
				+ "box span %,.0f blocks%n", bestCells, bestSpan);

		long started = System.nanoTime();
		BasinNetwork network = new BasinNetwork(
				best, world.uplift(), world.moisture().gating(), basins, settings);
		long built = System.nanoTime();

		FlowLattice flow = network.lattice();

		double channelLength = 0.0;
		double landArea = 0.0;
		double maxDischarge = 0.0;
		int[] orders = new int[12];

		for (int i = 0; i < flow.size(); i++) {
			if (flow.surface(i) > settings.baseLevelY()) {
				landArea += flow.spacing() * flow.spacing();
			}

			maxDischarge = Math.max(maxDischarge, flow.accumulated(i));
		}

		System.out.printf("    lattice            %d x %d, built in %,d ms%s%n",
				flow.width(), flow.depth(), (built - started) / 1_000_000,
				network.clamped() ? "  *** CLAMPED" : "");
		int landCells = 0;
		double totalFlow = 0.0;
		int terminals = 0;

		for (int i = 0; i < flow.size(); i++) {
			if (flow.surface(i) > settings.baseLevelY()) {
				landCells++;
			}

			if (flow.downstream(i) < 0) {
				terminals++;
				totalFlow += flow.accumulated(i);
			}
		}

		System.out.printf("    land cells         %,d of %,d in the box%n", landCells, flow.size());
		System.out.printf("    terminal cells     %,d, carrying %.3f total%n", terminals, totalFlow);
		System.out.printf("    flood reached      %,d of %,d cells%s%n",
				flow.processed(), flow.size(),
				flow.processed() == flow.size() ? "" : "  *** CELLS NEVER VISITED");
		System.out.printf("    runoff produced    %.3f total, %.3f arrives   [%.1f%% conserved]%n",
				network.totalGain(), totalFlow,
				network.totalGain() <= 0.0 ? 0.0 : 100.0 * totalFlow / network.totalGain());
		System.out.printf("    rain %.5f, deficit %.3f, runoff ratio %.3f   [Earth 0.35]%n",
				network.meanRain(), network.meanDeficit(), network.runoffRatio());
		System.out.printf("    mean retention     %.5f per cell, so %.1f%% survives 100,000 blocks%n",
				network.meanRetention(), 100.0 * Math.pow(network.meanRetention(), 100));
		System.out.printf("    channel segments   %,d%n", network.segmentCount());
		System.out.printf("    threshold          %.6f, max discharge %.4f%n",
				network.threshold(), maxDischarge);

		// Drainage density: area divided by total channel length is the mean distance
		// between neighbouring channels, which is the number the design targets.
		BasinNetwork.Nearest probe = new BasinNetwork.Nearest();
		double totalNearest = 0.0;
		int probes = 0;

		for (int iz = 0; iz < flow.depth(); iz += 3) {
			for (int ix = 0; ix < flow.width(); ix += 3) {
				int i = flow.index(ix, iz);

				if (flow.surface(i) <= settings.baseLevelY()) {
					continue;
				}

				network.nearestTwo(flow.worldX(ix), flow.worldZ(iz), probe);

				if (probe.found()) {
					totalNearest += probe.distance1;
					probes++;
				}
			}
		}

		System.out.printf("    channel spacing    %,.0f blocks   [tier 2 target %,.0f, "
				+ "1,500 to 3,000 after tier 3]%n",
				network.channelSpacing(), settings.channelSpacingTargetBlocks());
		System.out.printf("    mean distance to nearest channel %,.0f blocks%n",
				probes == 0 ? 0.0 : totalNearest / probes);
		System.out.printf("    lakes              %.2f%% of land, playas %.2f%%, "
				+ "%d terminal lakes, %s%n",
				100.0 * network.lakeAreaShare(), 100.0 * network.playaAreaShare(),
				network.terminalLakeCount(),
				String.format("%.1f%% endorheic", 100.0 * network.endorheicShare()));
		System.out.printf("    closed basins      %d, %d sink cells, %d endorheic cells of %d land%n",
				network.closedDepressionCount(), network.terminalSinkCount(),
				network.endorheicCellCount(), network.landCells());
		System.out.printf("    monotonic          %s, %d violations, worst rise %.2f blocks%n",
				network.monotonic() ? "YES" : "NO", network.monotonicViolations(),
				network.worstRise());

		// Swept rather than chosen, the same way the potential-evaporation scale was.
		// The target is Earth's roughly 18 percent of land draining to no sea.
		System.out.println();
		System.out.println("    closed-basin sill depth sweep   [Earth: about 18% endorheic]");

		for (double depth : new double[] {0.0, 10.0, 25.0, 50.0, 100.0}) {
			BasinNetwork swept = new BasinNetwork(
					best, world.uplift(), world.moisture().gating(), basins,
					settings.withClosedBasinDepth(depth));

			System.out.printf("      sill >= %,6.0f blocks  ->  %5.1f%% endorheic, "
					+ "%4d closed basins, %d terminal lakes, playas %.2f%%%n",
					depth, 100.0 * swept.endorheicShare(), swept.closedDepressionCount(),
					swept.terminalLakeCount(), 100.0 * swept.playaAreaShare());
		}

		// Exercise the carve so the clamp counter has something to report.
		FlowLattice probeFlow = network.lattice();

		// Land only. Below base level there is no channel to be in the valley of, so the
		// nearest one is some way inland and above; counting those would report the
		// coastline as a carve defect.
		for (int iz = 0; iz < probeFlow.depth(); iz += 2) {
			for (int ix = 0; ix < probeFlow.width(); ix += 2) {
				if (probeFlow.surface(probeFlow.index(ix, iz)) <= settings.baseLevelY()) {
					continue;
				}

				world.terrain().heightAt(probeFlow.worldX(ix), probeFlow.worldZ(iz));
			}
		}

		System.out.printf("    channel above column   %,d of %,d land samples (%.1f%%), "
				+ "mean %.2f blocks, worst %.1f   [steep ground, not a defect]%n",
				world.terrain().inversionClamps(), world.terrain().inversionSamples(),
				world.terrain().inversionSamples() == 0 ? 0.0
						: 100.0 * world.terrain().inversionClamps()
								/ world.terrain().inversionSamples(),
				world.terrain().meanInversionExcess(), world.terrain().worstInversionExcess());

		printIncision(world, probeFlow, settings);
		printSeamTest(world, probeFlow, settings);
		printChannelGradientSweep(world, network, basins, best, settings);
		printCreekStatistics(world, network, settings);
		printGradientSweep(world, network, settings);
		printTrunkTransect(network, settings, orders, channelLength);
	}

	/**
	 * Sweeps the channel gradient against how deeply rivers cut.
	 *
	 * <p>The constant sets how fast the equilibrium profile climbs inland, and it works
	 * backwards from the obvious direction: a <i>smaller</i> value keeps the profile low
	 * further upstream, so there is more ground above it to remove. Too small and the
	 * profile stays near sea level across a continent, giving canyons everywhere; too
	 * large and it meets the ground a short way inland and only coasts incise.
	 *
	 * <p>Measured at the channels themselves rather than through the finished surface, so
	 * it separates what the profile does from what the uplift surface was already doing.
	 */
	private static void printChannelGradientSweep(
			final TerrainModel.Snapshot world, final BasinNetwork current,
			final BasinIndex basins, final long outlet, final DrainageSettings settings) {
		System.out.println();
		System.out.println("      channel gradient sweep   [valley relief on Earth, scaled: "
				+ "20 to 80 blocks in hills, 170 to 330 in mountains]");

		for (double scale : new double[] {0.0002, 0.0005, 0.001, 0.002, 0.005}) {
			BasinNetwork swept = new BasinNetwork(
					outlet, world.uplift(), world.moisture().gating(), basins,
					settings.withChannelGradient(scale));

			System.out.printf("        scale %.4f  ->  mean cut at channel %6.1f blocks, "
					+ "deepest %7.1f, over 10 blocks on %5.1f%% of channels, "
					+ "monotonic %s%n",
					scale, swept.meanChannelIncision(), swept.deepestIncision(),
					100.0 * swept.deeplyIncisedShare(),
					swept.monotonic() ? "yes" : "NO");
		}
	}

	/**
	 * Tests whether terrain steps at tier 1 cell boundaries, and why.
	 *
	 * <p>Basin identity is a lookup into an 8,000-block lattice, so it is a piecewise
	 * constant function of position with its steps on that grid. Two columns four blocks
	 * apart across a cell edge can therefore be handed different basins, different tier 2
	 * solves, and different integrated channel profiles, which is a discontinuity in the
	 * finished height wherever the cell edge is not the real divide.
	 *
	 * <p><b>Symptom and mechanism are measured separately, because the symptom alone
	 * cannot tell them apart.</b> Terrain is genuinely rough, so a jump across a boundary
	 * proves nothing on its own. The decisive comparison is between straddling pairs whose
	 * basin changes and straddling pairs whose basin does not: same grid line, same
	 * separation, same terrain, differing only in whether the lookup switched basins. If
	 * only the switching pairs jump, the lattice is the cause and not a coincidence.
	 *
	 * <p>Worth measuring rather than asserting. The first explanation offered for the
	 * rectangular seams was creek stream identity, which turned out to be a real bug that
	 * was not this one: fixing it restored eighty thousand dropped branches and left the
	 * seams exactly where they were.
	 */
	private static void printSeamTest(
			final TerrainModel.Snapshot world, final FlowLattice flow,
			final DrainageSettings settings) {
		System.out.println();
		System.out.printf("    SEAM TEST at the %,.0f-block province lattice%n",
				settings.provinceLatticeBlocks());

		seamAxis(world, flow, settings, true);
		seamAxis(world, flow, settings, false);
	}

	/**
	 * One axis of the seam test.
	 *
	 * <p>Both are run because the seams are rectangular. A step on one axis only would
	 * mean something quite different from a step on both, and the two share every line of
	 * this code apart from which coordinate the boundary lies on.
	 */
	private static void seamAxis(
			final TerrainModel.Snapshot world, final FlowLattice flow,
			final DrainageSettings settings, final boolean alongX) {
		double lattice = settings.provinceLatticeBlocks();
		double step = 4.0;

		double minX = flow.worldX(0);
		double maxX = flow.worldX(flow.width() - 1);
		double minZ = flow.worldZ(0);
		double maxZ = flow.worldZ(flow.depth() - 1);

		double boundaryMin = alongX ? minX : minZ;
		double boundaryMax = alongX ? maxX : maxZ;
		double laneMin = alongX ? minZ : minX;
		double laneMax = alongX ? maxZ : maxX;

		// Accumulators, in four groups: every straddling pair, the ones whose basin
		// changed, the ones whose basin did not, and an interior control at mid-cell.
		double[] sum = new double[4];
		double[] worst = new double[4];
		int[] count = new int[4];
		double bedJumpSum = 0.0;
		double bedJumpWorst = 0.0;

		for (long cell = (long) Math.ceil(boundaryMin / lattice);
				cell <= (long) Math.floor(boundaryMax / lattice); cell++) {
			double boundary = cell * lattice;

			for (double lane = laneMin; lane <= laneMax; lane += lattice * 0.25) {
				double aX = alongX ? boundary - step * 0.5 : lane;
				double aZ = alongX ? lane : boundary - step * 0.5;
				double bX = alongX ? boundary + step * 0.5 : lane;
				double bZ = alongX ? lane : boundary + step * 0.5;

				// Land only. Below base level the nearest channel is inland and above, so
				// ocean pairs would report the coastline rather than a seam.
				if (world.uplift().heightAt(aX, aZ) <= settings.baseLevelY()
						|| world.uplift().heightAt(bX, bZ) <= settings.baseLevelY()) {
					continue;
				}

				double jump = Math.abs(world.terrain().heightAt(bX, bZ)
						- world.terrain().heightAt(aX, aZ));
				boolean switched = world.drainage().basins().outletAt(aX, aZ)
						!= world.drainage().basins().outletAt(bX, bZ);

				sum[0] += jump;
				worst[0] = Math.max(worst[0], jump);
				count[0]++;

				int group = switched ? 1 : 2;
				sum[group] += jump;
				worst[group] = Math.max(worst[group], jump);
				count[group]++;

				if (switched) {
					double bedJump = Math.abs(
							world.drainage().sample(bX, bZ).channelElevation()
									- world.drainage().sample(aX, aZ).channelElevation());
					bedJumpSum += bedJump;
					bedJumpWorst = Math.max(bedJumpWorst, bedJump);
				}

				// The control: the same separation and the same lane, half a cell away
				// from any boundary, so it carries the terrain's own roughness and nothing
				// else. Whatever this reads is the number a seam has to beat.
				double inside = boundary + lattice * 0.5;
				double cX = alongX ? inside - step * 0.5 : lane;
				double cZ = alongX ? lane : inside - step * 0.5;
				double dX = alongX ? inside + step * 0.5 : lane;
				double dZ = alongX ? lane : inside + step * 0.5;

				if (world.uplift().heightAt(cX, cZ) <= settings.baseLevelY()
						|| world.uplift().heightAt(dX, dZ) <= settings.baseLevelY()) {
					continue;
				}

				double flat = Math.abs(world.terrain().heightAt(dX, dZ)
						- world.terrain().heightAt(cX, cZ));
				sum[3] += flat;
				worst[3] = Math.max(worst[3], flat);
				count[3]++;
			}
		}

		double switchedMean = mean(sum[1], count[1]);
		double sameMean = mean(sum[2], count[2]);
		double interiorMean = mean(sum[3], count[3]);

		System.out.printf("      %s boundaries, %,d land pairs, basin switched on %.1f%%%n",
				alongX ? "east-west " : "north-south", count[0],
				count[0] == 0 ? 0.0 : 100.0 * count[1] / count[0]);
		System.out.printf("        basin switched     mean %6.2f blocks, worst %7.1f   "
				+ "(bed jump mean %.1f, worst %.1f)%n",
				switchedMean, worst[1], mean(bedJumpSum, count[1]), bedJumpWorst);
		System.out.printf("        basin unchanged    mean %6.2f blocks, worst %7.1f%n",
				sameMean, worst[2]);
		System.out.printf("        mid-cell control   mean %6.2f blocks, worst %7.1f%n",
				interiorMean, worst[3]);
		System.out.printf("        switched / control %.1fx%s%n",
				interiorMean <= 0.0 ? 0.0 : switchedMean / interiorMean,
				interiorMean > 0.0 && switchedMean / interiorMean > 3.0
						? "   *** terrain steps where basin identity changes" : "   [no step]");
	}

	private static double mean(final double total, final int count) {
		return count == 0 ? 0.0 : total / count;
	}

	/**
	 * How much ground the carve actually removes.
	 *
	 * <p>The whole claim of the redesign is that rivers cut. If the finished surface sits
	 * on the uplift surface everywhere, then uplift is not being spent, it is just being
	 * copied, and the carve is a smoothing pass wearing a river's clothes.
	 */
	private static void printIncision(
			final TerrainModel.Snapshot world, final FlowLattice flow,
			final DrainageSettings settings) {
		double total = 0.0;
		double deepest = 0.0;
		int samples = 0;
		int cutTen = 0;
		int cutFifty = 0;

		for (int iz = 0; iz < flow.depth(); iz += 2) {
			for (int ix = 0; ix < flow.width(); ix += 2) {
				if (flow.surface(flow.index(ix, iz)) <= settings.baseLevelY()) {
					continue;
				}

				double x = flow.worldX(ix);
				double z = flow.worldZ(iz);
				double cut = world.uplift().heightAt(x, z) - world.terrain().heightAt(x, z);

				samples++;
				total += cut;
				deepest = Math.max(deepest, cut);

				if (cut > 10.0) {
					cutTen++;
				}

				if (cut > 50.0) {
					cutFifty++;
				}
			}
		}

		System.out.println();
		System.out.printf("    INCISION, over %,d land samples%n", samples);
		System.out.printf("      mean cut         %.1f blocks below the uplift budget%n",
				samples == 0 ? 0.0 : total / samples);
		System.out.printf("      deepest cut      %.1f blocks%n", deepest);
		System.out.printf("      cut over 10      %.1f%% of land%n",
				samples == 0 ? 0.0 : 100.0 * cutTen / samples);
		System.out.printf("      cut over 50      %.1f%% of land%n",
				samples == 0 ? 0.0 : 100.0 * cutFifty / samples);
	}

	/**
	 * Measures the creek trees against the statistics they were generated to carry.
	 *
	 * <p><b>Asking for a target and never checking the result is how the region weights
	 * came to produce zero inselberg plains.</b> The whole argument for synthesising
	 * creeks rather than routing them was that a tree carrying real Hortonian statistics
	 * is more faithful at this scale than routing over noise. That argument is only worth
	 * anything if the statistics actually come out where they were asked to.
	 */
	private static void printCreekStatistics(
			final TerrainModel.Snapshot world, final BasinNetwork network,
			final DrainageSettings settings) {
		FlowLattice flow = network.lattice();

		int patches = 0;
		int segments = 0;
		int branches = 0;
		int junctions = 0;
		int[] perLevel = new int[16];
		double[] hackSums = new double[4];
		double creekLength = 0.0;
		double lengthSum = 0.0;
		double areaSum = 0.0;

		double spanX = flow.width() * flow.spacing();
		double spanZ = flow.depth() * flow.spacing();

		for (double z = flow.originZ(); z < flow.originZ() + spanZ; z += 8_000.0) {
			for (double x = flow.originX(); x < flow.originX() + spanX; x += 8_000.0) {
				var patch = world.drainage().creeks().patchAt(x, z, network);

				patches++;
				segments += patch.segmentCount();
				branches += patch.branchCount();
				junctions += patch.junctionCount();
				creekLength += patch.totalLength();
				lengthSum += patch.meanBranchLength() * patch.branchCount();
				areaSum += patch.meanBranchArea() * patch.branchCount();

				int[] levels = patch.branchesPerLevel();

				for (int level = 0; level < perLevel.length; level++) {
					perLevel[level] += levels[level];
				}

				double[] sums = patch.hackSums();

				for (int term = 0; term < hackSums.length; term++) {
					hackSums[term] += sums[term];
				}
			}
		}

		double landArea = network.landCells() * flow.spacing() * flow.spacing();
		double trunkLength = 0.0;

		// Combined spacing: area over the total length of everything that carries water,
		// trunks and creeks together, which is what a player actually walks between.
		trunkLength = landArea / network.channelSpacing();

		double combined = creekLength + trunkLength <= 0.0
				? 0.0 : landArea / (creekLength + trunkLength);

		System.out.println();
		System.out.printf("    TIER 3, %d patches%n", patches);
		System.out.printf("      creek segments   %,d, %,d branches, %,d junctions%n",
				segments, branches, junctions);
		System.out.printf("      bifurcation      %.2f realised from %.0f attempted   [Horton 4]   levels %s%n",
				bifurcation(perLevel), settings.bifurcationRatio(),
				java.util.Arrays.toString(java.util.Arrays.copyOf(perLevel,
						settings.creekLevels() + 1)));
		System.out.printf("      Hack exponent    %.3f   [target %.2f]%n",
				hackExponent(hackSums, branches), settings.hackExponent());
		System.out.printf("      mean branch      %,.0f blocks over area %.4f%n",
				branches == 0 ? 0.0 : lengthSum / branches,
				branches == 0 ? 0.0 : areaSum / branches);
		System.out.printf("      channel spacing  %,.0f blocks with creeks, %,.0f without"
				+ "   [target 1,500 to 3,000]%n",
				combined, network.channelSpacing());
	}

	/**
	 * Sweeps the creek gradient against the channel spacing it produces.
	 *
	 * <p><b>The gradient decides how far a creek gets before it dies.</b> A creek climbs
	 * at this rate and stops where it meets the uplift budget, so a gradient steeper than
	 * the hillslope it is climbing kills every creek at its first step. This world's
	 * hillslopes rise at roughly 0.017, and the starting value of 0.06 was more than
	 * three times that, which is why creeks contributed almost no length.
	 */
	private static void printGradientSweep(
			final TerrainModel.Snapshot world, final BasinNetwork network,
			final DrainageSettings settings) {
		FlowLattice flow = network.lattice();
		double landArea = network.landCells() * flow.spacing() * flow.spacing();
		double trunkLength = landArea / network.channelSpacing();

		System.out.println();
		System.out.println("      creek gradient sweep   [target spacing 1,500 to 3,000]");

		for (double gradient : new double[] {0.002, 0.005, 0.010, 0.020, 0.060}) {
			DrainageSettings swept = settings.withCreekGradient(gradient);
			CreekTrees trees = new CreekTrees(world.seedForCreeks(), world.uplift(), swept);

			double creekLength = 0.0;
			int segments = 0;
			double spanX = flow.width() * flow.spacing();
			double spanZ = flow.depth() * flow.spacing();

			for (double z = flow.originZ(); z < flow.originZ() + spanZ; z += 8_000.0) {
				for (double x = flow.originX(); x < flow.originX() + spanX; x += 8_000.0) {
					var patch = trees.patchAt(x, z, network);
					creekLength += patch.totalLength();
					segments += patch.segmentCount();
				}
			}

			System.out.printf("        gradient %.3f  ->  spacing %,7.0f blocks, "
					+ "%,8d creek segments%n",
					gradient, landArea / (creekLength + trunkLength), segments);
		}
	}

	/**
	 * Hack's exponent, as the slope of log length against log drainage area.
	 *
	 * <p>Ordinary least squares over every branch generated. The trees are built with a
	 * length ratio and an area ratio chosen to imply this exponent, so recovering it is a
	 * check that the construction does what the arithmetic says, not an independent
	 * discovery. It has caught arithmetic that did not.
	 */
	private static double hackExponent(final double[] sums, final int branches) {
		if (branches < 2) {
			return 0.0;
		}

		double n = branches;
		double denominator = n * sums[2] - sums[1] * sums[1];

		if (Math.abs(denominator) < 1.0e-12) {
			return 0.0;
		}

		return (n * sums[3] - sums[1] * sums[0]) / denominator;
	}

	/**
	 * Horton's bifurcation ratio, measured off the generated tree.
	 *
	 * <p>The mean ratio between the count of branches at one level and the count at the
	 * next level up. Levels with too few branches to be meaningful are skipped rather
	 * than allowed to drag the mean around.
	 */
	private static double bifurcation(final int[] perLevel) {
		double total = 0.0;
		int pairs = 0;

		for (int level = 0; level + 1 < perLevel.length; level++) {
			if (perLevel[level] >= 4 && perLevel[level + 1] >= 1) {
				total += (double) perLevel[level] / perLevel[level + 1];
				pairs++;
			}
		}

		return pairs == 0 ? 0.0 : total / pairs;
	}

	/**
	 * Walks the main stem from headwater to mouth.
	 *
	 * <p>Follows the largest upstream branch at every junction, which is what "main
	 * stem" means. Printed headwater first so the elevation column reads the way water
	 * runs, and any rise in it is immediately visible as a number rather than having to
	 * be inferred from a colour.
	 */
	private static void printTrunkTransect(
			final BasinNetwork network, final DrainageSettings settings,
			final int[] orders, final double channelLength) {
		FlowLattice flow = network.lattice();

		int mouth = 0;
		double most = -1.0;

		for (int i = 0; i < flow.size(); i++) {
			if (flow.accumulated(i) > most) {
				most = flow.accumulated(i);
				mouth = i;
			}
		}

		int[] upstream = new int[flow.size()];
		double[] bestFlow = new double[flow.size()];
		java.util.Arrays.fill(upstream, -1);

		for (int i = 0; i < flow.size(); i++) {
			int next = flow.downstream(i);

			if (next >= 0 && flow.accumulated(i) > bestFlow[next]) {
				bestFlow[next] = flow.accumulated(i);
				upstream[next] = i;
			}
		}

		java.util.List<Integer> stem = new java.util.ArrayList<>();

		for (int i = mouth; i >= 0 && stem.size() < 4_000; i = upstream[i]) {
			stem.add(i);
		}

		java.util.Collections.reverse(stem);

		System.out.println();
		System.out.printf("    MAIN STEM, %d stations from headwater to mouth%n", stem.size());
		System.out.println("      station    x         z        filled  surface  discharge   drop");

		int stride = Math.max(1, stem.size() / 16);
		double previous = Double.NaN;

		for (int n = 0; n < stem.size(); n += stride) {
			int i = stem.get(n);
			double filled = flow.filled(i);
			double drop = Double.isNaN(previous) ? 0.0 : previous - filled;
			previous = filled;

			System.out.printf("      %7d  %,9.0f %,9.0f  %7.1f  %7.1f  %9.4f  %6.1f%s%n",
					n, flow.worldX(flow.cellX(i)), flow.worldZ(flow.cellZ(i)),
					filled, flow.surface(i), flow.accumulated(i), drop,
					drop < -0.001 ? "  *** RISES" : "");
		}
	}

	/**
	 * The units precipitation and evaporation actually come in.
	 *
	 * <p>Sizing an evaporation term against the wrong quantity is how
	 * {@code convergenceRainFactor} ended up 17 times too large and left the equator at
	 * 2 percent humidity. Saturation is 1.0 at the 15 degree reference and exponential
	 * from there, while precipitation runs in hundredths. They are not comparable and
	 * the factor between them has to be measured, not assumed.
	 *
	 * <p>Calibrated against a real number: over Earth's land, roughly 35 percent of
	 * precipitation becomes runoff and the rest returns to the air. That is the figure
	 * the potential-evaporation scale is chosen to reproduce.
	 */
	private static void printWaterBalance(final TerrainModel.Snapshot world) {
		MoistureField moisture = world.moisture().gating();
		HeightField terrain = world.coarse();

		int grid = 60;
		double span = 400_000.0;
		double step = span / grid;

		double totalRain = 0.0;
		double totalDeficit = 0.0;
		double maxRain = 0.0;
		double maxDeficit = 0.0;
		int land = 0;

		for (int iz = 0; iz < grid; iz++) {
			for (int ix = 0; ix < grid; ix++) {
				double worldX = -span * 0.5 + (ix + 0.5) * step;
				double worldZ = -span * 0.5 + (iz + 0.5) * step;

				if (terrain.heightAt(worldX, worldZ) <= MapPanel.SEA_LEVEL) {
					continue;
				}

				land++;

				var air = moisture.at(worldX, worldZ);
				double deficit = air.saturation() * (1.0 - air.humidity());

				totalRain += air.precipitation();
				totalDeficit += deficit;
				maxRain = Math.max(maxRain, air.precipitation());
				maxDeficit = Math.max(maxDeficit, deficit);
			}
		}

		double meanRain = totalRain / land;
		double meanDeficit = totalDeficit / land;

		System.out.println();
		System.out.printf("  WATER BALANCE, over %,d land samples%n", land);
		System.out.printf("    precipitation      mean %.5f, max %.5f%n", meanRain, maxRain);
		System.out.printf("    vapour deficit     mean %.5f, max %.5f%n", meanDeficit, maxDeficit);
		System.out.printf("    ratio deficit/rain %.1fx%n", meanDeficit / meanRain);

		// Turc-Pike: E = P / sqrt(1 + (P/PET)^2). Water-limited where PET dominates,
		// energy-limited where rain does, with no discontinuity between the two.
		for (double scale : new double[] {0.005, 0.01, 0.02, 0.04, 0.08}) {
			double runoff = 0.0;
			double rain = 0.0;
			int producing = 0;

			for (int iz = 0; iz < grid; iz++) {
				for (int ix = 0; ix < grid; ix++) {
					double worldX = -span * 0.5 + (ix + 0.5) * step;
					double worldZ = -span * 0.5 + (iz + 0.5) * step;

					if (terrain.heightAt(worldX, worldZ) <= MapPanel.SEA_LEVEL) {
						continue;
					}

					var air = moisture.at(worldX, worldZ);
					double potential = scale * air.saturation() * (1.0 - air.humidity());
					double p = air.precipitation();
					double evaporated = potential <= 0.0 ? 0.0
							: p / Math.sqrt(1.0 + (p / potential) * (p / potential));

					rain += p;
					runoff += p - evaporated;

					if (p - evaporated > p * 0.05) {
						producing++;
					}
				}
			}

			System.out.printf("    PET scale %.3f  ->  runoff %.1f%% of rain, "
					+ "%.0f%% of land producing   [Earth: 35%%]%n",
					scale, 100.0 * runoff / rain, 100.0 * producing / land);
		}
	}

	/**
	 * How basin size is distributed across one tile.
	 *
	 * <p>The shape matters more than the count. Real drainage is heavy-tailed: a few
	 * very large basins and a long tail of small coastal ones. If almost every basin
	 * is one or two cells, flow is fragmenting and the tier is not producing drainage
	 * at all, however plausible its total count looks.
	 */
	private static void printBasinSizes(final FlowLattice flow, final DrainageSettings settings) {
		Map<Integer, Integer> sizes = new HashMap<>();

		for (int i = 0; i < flow.size(); i++) {
			if (flow.surface(i) <= settings.baseLevelY()) {
				continue;
			}

			sizes.merge(flow.outletOf(i), 1, Integer::sum);
		}

		int[] counts = sizes.values().stream().mapToInt(Integer::intValue).sorted().toArray();

		if (counts.length == 0) {
			System.out.println("    no land");
			return;
		}

		int total = java.util.Arrays.stream(counts).sum();
		int singletons = (int) java.util.Arrays.stream(counts).filter(c -> c <= 2).count();
		int largest = counts[counts.length - 1];
		double cellArea = settings.provinceLatticeBlocks();

		System.out.printf("    basins             %,d over %,d land cells%n", counts.length, total);
		System.out.printf("    median size        %d cells%n", counts[counts.length / 2]);
		System.out.printf("    largest            %,d cells, about %,.0f blocks across%n",
				largest, Math.sqrt(largest) * cellArea);
		System.out.printf("    tiny (1 to 2)      %.1f%% of basins%n",
				100.0 * singletons / counts.length);
		System.out.printf("    largest 10 hold    %.1f%% of land%n",
				100.0 * java.util.Arrays.stream(counts, Math.max(0, counts.length - 10),
						counts.length).sum() / total);
	}

	private static void printRoughness2(final HeightField field, final double spacing) {
		int samples = 400;
		double total = 0.0;
		double previous = field.heightAt(0.0, 0.0);

		for (int i = 1; i <= samples; i++) {
			double height = field.heightAt(i * spacing, 0.0);
			total += Math.abs(height - previous);
			previous = height;
		}

		System.out.printf("    step at %,7.0f blocks  mean |dh| %,7.1f blocks%n",
				spacing, total / samples);
	}

	/**
	 * Mean absolute height difference between neighbouring samples at a given spacing.
	 *
	 * <p>On real terrain this falls as the spacing falls: two points 250 blocks apart
	 * are more alike than two points 8,000 apart. If it stops falling, or rises, the
	 * coarser spacing is aliasing a field finer than itself.
	 */
	private static void printRoughness(final UpliftHeight uplift, final double spacing) {
		int samples = 400;
		double total = 0.0;
		double previous = uplift.heightAt(0.0, 0.0);

		for (int i = 1; i <= samples; i++) {
			double height = uplift.heightAt(i * spacing, 0.0);
			total += Math.abs(height - previous);
			previous = height;
		}

		System.out.printf("  step at %,7.0f blocks  mean |dh| %,7.1f blocks%n",
				spacing, total / samples);
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
