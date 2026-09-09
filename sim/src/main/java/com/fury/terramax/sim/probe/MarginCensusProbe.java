package com.fury.terramax.sim.probe;

import java.util.HashMap;
import java.util.Map;

import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/** How much of the world each class of margin owns, and how tall it stands. */
public final class MarginCensusProbe implements Probe {
	@Override
	public String name() {
		return "census";
	}

	@Override
	public String description() {
		return "share of the world, height and relief per class of margin";
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

		Map<String, double[]> byClass = new HashMap<>();
		int land = 0;

		for (int iz = 0; iz < Probes.GRID; iz++) {
			for (int ix = 0; ix < Probes.GRID; ix++) {
				TectonicHeight.Sample sample =
						tectonic.sample(originX + ix * step, originZ + iz * step);

				// count, total height, tallest height, total |relief|, inside a range,
				// below sea level
				double[] acc = byClass.computeIfAbsent(
						Probes.marginClass(sample),
						key -> new double[] {0.0, 0.0, Double.NEGATIVE_INFINITY, 0.0, 0.0, 0.0});

				acc[0]++;
				acc[1] += sample.height();
				acc[2] = Math.max(acc[2], sample.height());
				acc[3] += Math.abs(sample.relief());

				if (sample.plate().boundaryDistance() < rangeWidth) {
					acc[4]++;
				}

				// A mean height says nothing about a landform whose whole character is
				// that it alternates. A province averaging y=30 can be half ranges at
				// y=200 and half basins under water, and only one of those halves grows
				// salt flats.
				if (sample.height() <= 0.0) {
					acc[5]++;
				}

				if (sample.height() > 0.0) {
					land++;
				}
			}
		}

		int total = Probes.GRID * Probes.GRID;

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"  margin class census, %,d columns over %,.0f blocks", total, span));

		out.row("    %-22s %7s %8s %8s %10s %9s %9s",
				"class", "share", "mean y", "max y", "mean|rel|", "in range", "under sea");

		byClass.entrySet().stream()
				.sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
				.forEach(entry -> {
					double[] acc = entry.getValue();

					out.row("    %-22s %6.1f%% %8.0f %8.0f %10.0f %8.1f%% %8.1f%%",
							entry.getKey(), 100.0 * acc[0] / total, acc[1] / acc[0], acc[2],
							acc[3] / acc[0], 100.0 * acc[4] / acc[0], 100.0 * acc[5] / acc[0]);
				});

		out.row("    %-22s %6.1f%%", "land", 100.0 * land / total);
		out.blank();

		return out.build();
	}
}
