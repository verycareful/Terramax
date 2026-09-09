package com.fury.terramax.sim.probe;

import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/**
 * The fraction of its profile a range actually gets to build.
 *
 * <p>Relief is a sum of weighted profiles over a divisor, so the multiplier finally
 * applied to a range's shape is {@code sum(f.w) / max(max(f), sum(f.w))}. If that sits
 * below one along an ordinary stretch of margin then every range in the world is
 * quietly shortened by the shortfall, and neither a height setting nor a profile will
 * show why. This computes the multiplier the way the generator does, so tuning the
 * junction softness has a number to aim at.
 *
 * <p><b>Known divergence, carried over unchanged.</b> The falloff here uses the shared
 * range half-width, while the generator now asks each type for its own reach: sutures
 * and fault blocks use less. So this understates the envelope wherever one of those is
 * the margin in reach. Left alone for now because correcting it changes every recorded
 * number at once, and that deserves its own measurement rather than riding along with
 * a refactor.
 */
public final class RangeEnvelopeProbe implements Probe {
	/** Samples per axis. Coarser than the census because each column walks every margin. */
	private static final int GRID = 160;

	/** Only columns this close to a margin count, as a fraction of the half-width. */
	private static final double INNER_HALF = 0.5;

	private static final double FULL_HEIGHT = 0.95;
	private static final double STARVED = 0.5;

	@Override
	public String name() {
		return "envelope";
	}

	@Override
	public String description() {
		return "how much of its profile a range is actually allowed to build";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TectonicHeight tectonic = model.snapshot().uplift().tectonic();
		var plates = tectonic.plates();

		double spacing = model.plateSettings().crustSpacingBlocks();
		double rangeWidth = model.terrainSettings().rangeWidthBlocks(spacing);
		double blendWidth = model.terrainSettings().blendWidthBlocks(spacing);

		MapView view = context.view();
		double span = view.spanBlocks();
		double step = span / GRID;
		double originX = view.centreX() - span * 0.5;
		double originZ = view.centreZ() - span * 0.5;

		double total = 0.0;
		double bestWeight = 0.0;
		int counted = 0;
		int full = 0;
		int starved = 0;

		for (int iz = 0; iz < GRID; iz++) {
			for (int ix = 0; ix < GRID; ix++) {
				double x = originX + ix * step;
				double z = originZ + iz * step;

				// Only where a range is meant to stand. Deep in a plate interior the
				// answer is correctly nothing, and would drag the average down.
				if (tectonic.sample(x, z).plate().boundaryDistance() > rangeWidth * INNER_HALF) {
					continue;
				}

				// weighted total, strongest falloff, strongest margin weight
				double[] acc = new double[3];

				plates.forEachBoundary(x, z, rangeWidth, blendWidth, boundary -> {
					double falloff = Probes.dome(boundary.boundaryDistance(), rangeWidth);

					acc[0] += falloff * boundary.weight();
					acc[1] = Math.max(acc[1], falloff);
					acc[2] = Math.max(acc[2], boundary.weight());
				});

				if (acc[1] <= 0.0) {
					continue;
				}

				double scale = acc[0] / Math.max(acc[1], acc[0]);

				total += scale;
				bestWeight += acc[2];
				counted++;

				if (scale >= FULL_HEIGHT) {
					full++;
				} else if (scale < STARVED) {
					starved++;
				}
			}
		}

		int seen = counted;

		return ProbeResult.titled(String.format(
						"  height actually built, on the inner half of ranges, %,d columns", seen))
				.row("    mean fraction of profile     %6.3f   (1.000 is a range at full height)",
						seen == 0 ? 0.0 : total / seen)
				.row("    strongest margin weight      %6.3f", seen == 0 ? 0.0 : bestWeight / seen)
				.row("    at full height               %6.1f%%", seen == 0 ? 0.0 : 100.0 * full / seen)
				.row("    below half                   %6.1f%%", seen == 0 ? 0.0 : 100.0 * starved / seen)
				.blank()
				.build();
	}
}
