package com.fury.terramax.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.fury.terramax.core.util.PoissonDisk;
import com.fury.terramax.core.util.VoronoiSolver;

/**
 * Entry point for the standalone terrain simulator.
 *
 * <p>The simulator exists because Terramax's scale makes in-game iteration
 * impractical: with plate centres roughly 100,000 blocks apart, reaching a plate
 * boundary means flying 50,000 blocks. Rendering the same maths to a PNG takes a
 * second.
 *
 * <p>It links {@code :core} only. If this class ever needs something from
 * {@code :mod}, that is a sign terrain maths has leaked into the mod and belongs
 * in {@code :core} instead.
 */
public final class SimulatorMain {
	/** Plate spacing from the design spec. */
	private static final double PLATE_SPACING_BLOCKS = 100_000.0;

	/**
	 * Well below the 0.5 ceiling. Enough to break up the grid, while keeping a
	 * minimum plate separation of {@code spacing * (1 - 2 * 0.35)}, i.e. 30,000
	 * blocks. Plates smaller than that would be a poor use of a tectonic model.
	 */
	private static final double PLATE_JITTER = 0.35;

	private static final long SEED = 1L;
	private static final int IMAGE_PIXELS = 1024;

	/** Wide enough to hold roughly 8x8 plates. */
	private static final double CONTINENTAL_SPAN = PLATE_SPACING_BLOCKS * 8.0;

	/** Roughly one plate, to inspect a single boundary. */
	private static final double LOCAL_SPAN = PLATE_SPACING_BLOCKS * 1.5;

	private static final Path OUTPUT_DIR = Path.of("build", "renders");

	private SimulatorMain() {
	}

	public static void main(final String[] args) throws IOException {
		PoissonDisk sites = new PoissonDisk(SEED, PLATE_SPACING_BLOCKS, PLATE_JITTER);
		VoronoiSolver solver = new VoronoiSolver(sites);

		System.out.println("Terramax terrain simulator");
		System.out.printf("  seed              %d%n", SEED);
		System.out.printf("  plate spacing     %,.0f blocks%n", sites.spacing());
		System.out.printf("  jitter            %.2f%n", PLATE_JITTER);
		System.out.printf("  min separation    %,.0f blocks%n", sites.minimumSeparation());
		System.out.println();

		Files.createDirectories(OUTPUT_DIR);

		MapView continental = new MapView(0, 0, CONTINENTAL_SPAN, IMAGE_PIXELS);
		MapView local = new MapView(0, 0, LOCAL_SPAN, IMAGE_PIXELS);

		write("plates-continental", continental, solver, MapRenderer.Layer.PLATES_WITH_EDGES);
		write("boundaries-continental", continental, solver, MapRenderer.Layer.BOUNDARY_DISTANCE);
		write("plates-local", local, solver, MapRenderer.Layer.PLATES_WITH_EDGES);

		System.out.println();
		System.out.println("Wrote to " + OUTPUT_DIR.toAbsolutePath());
	}

	private static void write(
			final String name,
			final MapView view,
			final VoronoiSolver solver,
			final MapRenderer.Layer layer) throws IOException {
		long start = System.nanoTime();
		var image = MapRenderer.render(solver, view, layer);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		Path target = OUTPUT_DIR.resolve(name + ".png");
		ImageIO.write(image, "PNG", target.toFile());

		System.out.printf("  %-24s %,.0f blocks across, %,.0f blocks/pixel, %d ms%n",
				name, view.spanBlocks(), view.blocksPerPixel(), elapsedMs);
	}
}
