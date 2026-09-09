package com.fury.terramax.sim.probe;

import java.util.HashMap;
import java.util.Map;

import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/**
 * Mean relief against distance from the boundary, which is the range's shape.
 *
 * <p>The row for a type is its profile, read left to right out from the margin. Most
 * types fall away monotonically. A row that does anything else is telling you
 * something: fault blocks oscillate, because their profile repeats, and that
 * oscillation surviving the average over thousands of columns is what proves the
 * periodic structure is real rather than an artefact of one transect.
 */
public final class ReliefProfileProbe implements Probe {
	/** Bins across a range half-width. Twelve resolves a profile without fragmenting it. */
	private static final int BINS = 12;

	@Override
	public String name() {
		return "profile";
	}

	@Override
	public String description() {
		return "mean relief per distance bin out from the boundary, per class";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TectonicHeight tectonic = model.snapshot().uplift().tectonic();
		double rangeWidth = model.terrainSettings()
				.rangeWidthBlocks(model.plateSettings().crustSpacingBlocks());

		MapView view = context.view();
		double span = view.spanBlocks();
		double step = span / Probes.GRID;
		double originX = view.centreX() - span * 0.5;
		double originZ = view.centreZ() - span * 0.5;
		double binWidth = rangeWidth / BINS;

		Map<String, double[]> totals = new HashMap<>();
		Map<String, double[]> counts = new HashMap<>();

		for (int iz = 0; iz < Probes.GRID; iz++) {
			for (int ix = 0; ix < Probes.GRID; ix++) {
				TectonicHeight.Sample sample =
						tectonic.sample(originX + ix * step, originZ + iz * step);

				int bin = (int) (sample.plate().boundaryDistance() / binWidth);

				if (bin >= BINS) {
					continue;
				}

				String key = Probes.marginClass(sample);

				totals.computeIfAbsent(key, k -> new double[BINS])[bin] += sample.relief();
				counts.computeIfAbsent(key, k -> new double[BINS])[bin]++;
			}
		}

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"  relief profile, mean blocks per %,.0f-block bin out from the boundary", binWidth));

		totals.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> {
					double[] sum = entry.getValue();
					double[] count = counts.get(entry.getKey());
					StringBuilder row = new StringBuilder(String.format("    %-22s", entry.getKey()));

					for (int bin = 0; bin < BINS; bin++) {
						row.append(count[bin] == 0.0
								? String.format("%7s", "-")
								: String.format("%7.0f", sum[bin] / count[bin]));
					}

					out.row(row.toString());
				});

		out.blank();

		return out.build();
	}
}
