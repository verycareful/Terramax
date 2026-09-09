package com.fury.terramax.sim.probe;

import com.fury.terramax.sim.TerrainModel;

/**
 * Time a drainage query costs on top of the terrain column underneath it.
 *
 * <p>Warmed first. A cold measurement here would be measuring basin construction and
 * JIT compilation rather than the query, which is the thing being asked about.
 *
 * <p>Also reports how often the carve had to be clamped to stop a channel standing
 * above its own column. That should be zero, and counting it matters because a guard
 * that fires often is not a guard doing its job, it is a model that is wrong
 * somewhere, and without a number nobody would ever find out.
 */
public final class DrainageCostProbe implements Probe {
	private static final int CHUNK = 16;
	private static final int BENCH_CHUNKS = 64;
	private static final int WARMUP_SAMPLES = 4_096;
	private static final double BASE_X = 120_000.0;
	private static final double BASE_Z = -330_000.0;

	@Override
	public String name() {
		return "drainage-cost";
	}

	@Override
	public String description() {
		return "milliseconds a chunk pays for drainage, and the inversion clamp count";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TerrainModel.Snapshot world = model.snapshot();

		for (int i = 0; i < WARMUP_SAMPLES; i++) {
			world.drainage().sample(BASE_X + (i % 64) * 4.0, BASE_Z + (i / 64) * 4.0);
		}

		long started = System.nanoTime();
		int columns = 0;

		for (int chunk = 0; chunk < BENCH_CHUNKS; chunk++) {
			double chunkX = BASE_X + (chunk % 8) * (double) CHUNK;
			double chunkZ = BASE_Z + (chunk / 8) * (double) CHUNK;

			for (int cz = 0; cz < CHUNK; cz++) {
				for (int cx = 0; cx < CHUNK; cx++) {
					world.drainage().sample(chunkX + cx, chunkZ + cz);
					columns++;
				}
			}
		}

		double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;

		ProbeResult.Builder out = ProbeResult.titled("");

		out.blank();
		out.row("DRAINAGE COST");
		out.row("  %,d columns over %d chunks in %,.0f ms", columns, BENCH_CHUNKS, elapsedMs);
		out.row("  %.3f ms per chunk from drainage alone", elapsedMs / BENCH_CHUNKS);
		out.row("  basins solved %,d, creek patches %,d",
				world.drainage().solvedBasins(), world.drainage().creeks().cachedPatches());

		long clamps = world.terrain().inversionClamps() + world.coarse().inversionClamps();

		out.row("  channel above column %,d, mean %.2f blocks, worst %.1f"
						+ "   [steep ground, not a defect]",
				clamps,
				Math.max(world.terrain().meanInversionExcess(),
						world.coarse().meanInversionExcess()),
				Math.max(world.terrain().worstInversionExcess(),
						world.coarse().worstInversionExcess()));

		return out.build();
	}
}
