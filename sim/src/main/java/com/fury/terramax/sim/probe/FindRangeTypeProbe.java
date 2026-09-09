package com.fury.terramax.sim.probe;

import java.util.stream.IntStream;

import com.fury.terramax.core.terrain.RangeType;
import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/**
 * Where in the world a given range type is thickest on the ground.
 *
 * <p>Buckets the sweep coarsely and ranks buckets by how much of each is the type
 * asked for, rather than reporting the first hit. A single column of a type says
 * nothing about whether the landform around it holds together, and a render pointed at
 * one shows a fragment of something surrounded by something else.
 *
 * <p>This is the probe that produces {@link Place}s worth having, and the reason
 * {@code Place} exists: fault blocks are 1,200 blocks apart, which is one and a half
 * pixels in a continental view, and the fixed local view sat on a coastline containing
 * none of them. Two renders showed nothing and neither said so.
 */
public final class FindRangeTypeProbe implements Probe {
	/**
	 * Sweep cells per side of a bucket.
	 *
	 * <p>Sixteen cells is around 42,000 blocks at the continental view, a few margins
	 * across. Wide enough that a bucket scoring highly is a province rather than one
	 * lucky margin, narrow enough that its centre is somewhere worth pointing a render.
	 */
	private static final int BUCKET_CELLS = 16;

	/** Places reported. Enough to pick a second if the first disappoints. */
	private static final int REPORT_LIMIT = 6;

	@Override
	public String name() {
		return "find";
	}

	@Override
	public String description() {
		return "where a range type is thickest; takes a type name, such as fault_block";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		RangeType target = RangeType.valueOf(context.arg(0).toUpperCase());

		TectonicHeight tectonic = model.snapshot().uplift().tectonic();

		MapView view = context.view();
		double span = view.spanBlocks();
		double step = span / Probes.GRID;
		double originX = view.centreX() - span * 0.5;
		double originZ = view.centreZ() - span * 0.5;
		int side = Probes.GRID / BUCKET_CELLS;

		double[] hits = new double[side * side];
		double[] height = new double[side * side];

		for (int iz = 0; iz < side * BUCKET_CELLS; iz++) {
			for (int ix = 0; ix < side * BUCKET_CELLS; ix++) {
				TectonicHeight.Sample sample =
						tectonic.sample(originX + ix * step, originZ + iz * step);

				if (sample.type() != target) {
					continue;
				}

				int bucket = (iz / BUCKET_CELLS) * side + ix / BUCKET_CELLS;

				hits[bucket]++;
				height[bucket] += sample.height();
			}
		}

		double bucketBlocks = step * BUCKET_CELLS;
		double total = BUCKET_CELLS * (double) BUCKET_CELLS;
		String label = target.name().toLowerCase().replace('_', ' ');

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"WHERE %s is thickest, buckets of %,.0f blocks", label, bucketBlocks));

		IntStream.range(0, hits.length)
				.boxed()
				.sorted((a, b) -> Double.compare(hits[b], hits[a]))
				.limit(REPORT_LIMIT)
				.forEach(bucket -> {
					double share = 100.0 * hits[bucket] / total;
					double meanY = hits[bucket] == 0.0 ? 0.0 : height[bucket] / hits[bucket];
					double centreX = originX + (bucket % side + 0.5) * bucketBlocks;
					double centreZ = originZ + (bucket / side + 0.5) * bucketBlocks;

					out.row("  %,10.0f %,10.0f   %5.1f%% of bucket   mean y %5.0f",
							centreX, centreZ, share, meanY);

					// Only somewhere the landform actually dominates. A bucket holding a
					// couple of columns of the type is not a place, and offering it as
					// one sends a render at ground that will not show anything.
					if (share > 0.0) {
						out.place(new Place(
								String.format("%s, %.0f%% of bucket", label, share),
								centreX, centreZ, bucketBlocks));
					}
				});

		out.blank();

		return out.build();
	}
}
