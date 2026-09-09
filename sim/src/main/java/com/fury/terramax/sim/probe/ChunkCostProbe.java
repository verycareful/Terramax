package com.fury.terramax.sim.probe;

import com.fury.terramax.sim.TerrainModel;

/**
 * Time to build one chunk's surface, single threaded.
 *
 * <p><b>Read the caveat before quoting a number from this.</b> Chunk timings swing
 * further between runs than between builds. The same build read 14.84, 11.43 and 11.49
 * ms on three consecutive passes in one JVM, and room temperature moves them. A
 * performance delta claimed from one reading against one reading is not a result, and
 * one was published in a commit message before this was understood.
 *
 * <p>Takes an optional pass count, defaulting to one. Passing three is how the
 * command line uses it, because one reading is not a measurement and seeing the spread
 * is the only honest way to read the middle of it.
 *
 * <p>There is a second reason a single reading lies, independent of the machine. A
 * column may trigger a basin solve, which is a priority flood over a province lattice
 * and orders of magnitude dearer than a column. Basins are cached by outlet, so the
 * figure partly measures how many solves landed inside the timed window rather than in
 * the warmup, and that shifts whenever terrain shape shifts.
 */
public final class ChunkCostProbe implements Probe {
	private static final int WARMUP_COLUMNS = 20_000;
	private static final int BENCH_CHUNKS = 400;
	private static final double CHUNK_BUDGET_MS = 5.0;

	/** Chunk edge in blocks. */
	private static final int CHUNK = 16;

	@Override
	public String name() {
		return "chunk-cost";
	}

	@Override
	public String description() {
		return "milliseconds to build one chunk surface; takes an optional pass count";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		int passes = context.has(0) ? context.intArg(0) : 1;
		TerrainModel.Snapshot world = model.snapshot();

		ProbeResult.Builder out = ProbeResult.titled("");

		for (int pass = 0; pass < passes; pass++) {
			measure(world, out);
		}

		return out.build();
	}

	/**
	 * One timed pass.
	 *
	 * <p>Sampled away from the origin so it does not accidentally measure only plate
	 * interior, which is the cheap case: interiors exhaust the candidate search without
	 * finding a differing plate, margins usually stop early.
	 */
	private void measure(final TerrainModel.Snapshot world, final ProbeResult.Builder out) {
		double[] origins = {0.0, 120_000.0, -348_600.0, 75_600.0};

		// Warm up first. The first few thousand calls run interpreted, and reporting
		// those as the cost would overstate it several times over.
		for (int i = 0; i < WARMUP_COLUMNS; i++) {
			world.terrain().heightAt(i * 7.0, i * 13.0);
		}

		long start = System.nanoTime();
		int columns = 0;

		for (int chunk = 0; chunk < BENCH_CHUNKS; chunk++) {
			double baseX = origins[chunk % 2 * 2] + (chunk / 2) * (double) CHUNK;
			double baseZ = origins[chunk % 2 * 2 + 1] + (chunk / 2) * (double) CHUNK;

			for (int cz = 0; cz < CHUNK; cz++) {
				for (int cx = 0; cx < CHUNK; cx++) {
					world.terrain().heightAt(baseX + cx, baseZ + cz);
					columns++;
				}
			}
		}

		double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
		double perChunk = elapsedMs / BENCH_CHUNKS;

		out.blank();
		out.row("Generation cost, single-threaded:");
		out.row("  %,d columns over %d chunks in %,.0f ms", columns, BENCH_CHUNKS, elapsedMs);
		out.row("  %.2f ms per chunk surface", perChunk);
		out.row("  %,.0f columns per second", columns / (elapsedMs / 1000.0));

		// A rough budget. Vanilla spends a few ms per chunk on terrain shape, and a
		// generator wanting to keep up with a player flying needs to stay in that
		// region across the whole worker pool.
		if (perChunk > CHUNK_BUDGET_MS) {
			out.row("  *** OVER BUDGET: %.2f ms against a %.0f ms target", perChunk, CHUNK_BUDGET_MS);
		}
	}
}
