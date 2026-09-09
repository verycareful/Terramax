package com.fury.terramax.sim.probe;

import java.util.HashMap;
import java.util.Map;

import com.fury.terramax.core.fluvial.BasinIndex;
import com.fury.terramax.core.fluvial.DrainageSettings;
import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;
import com.fury.terramax.sim.WorldBounds;

/**
 * Basin count, lakes, playas, and how much land drains to no sea.
 *
 * <p><b>The largest basin figure is the margin assumption under measurement.</b>
 * Basins are keyed by outlet so that two province tiles containing the same straddling
 * basin agree by construction, and that holds only while the province margin exceeds
 * the largest basin. A figure approaching the margin means the bound is not safe on
 * this seed and the design has to widen it.
 *
 * <p>The endorheic and playa shares are how a terrain change is judged downstream:
 * they are what say whether closed basins are being made, and they need running
 * repeatedly while tuning. They used to be buried in a batch run that also produced
 * forty renders and took a quarter of an hour, which meant in practice they were
 * measured once per change rather than once per attempt.
 */
public final class BasinProbe implements Probe {
	/** Samples per axis. Land only, so the effective count is well under the square. */
	private static final int GRID = 200;

	/**
	 * How much wider than a basin its tile margin has to be.
	 *
	 * <p>A basin straddling a tile edge sits half in each tile, so half its span has to
	 * clear both extents, with room to spare for the divides around it.
	 */
	private static final double MARGIN_FACTOR = 1.5;

	@Override
	public String name() {
		return "basins";
	}

	@Override
	public String description() {
		return "basin count and span, plus lake, playa and endorheic shares of land";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TerrainModel.Snapshot world = model.snapshot();
		BasinIndex basins = world.basins();
		HeightField terrain = world.coarse();

		MapView view = context.view();
		double step = view.spanBlocks() / GRID;
		double minX = view.centreX() - view.spanBlocks() * 0.5;
		double minZ = view.centreZ() - view.spanBlocks() * 0.5;

		Map<Long, double[]> extents = new HashMap<>();
		int land = 0;

		for (int iz = 0; iz < GRID; iz++) {
			for (int ix = 0; ix < GRID; ix++) {
				double worldX = minX + (ix + 0.5) * step;
				double worldZ = minZ + (iz + 0.5) * step;

				if (terrain.heightAt(worldX, worldZ) <= WorldBounds.SEA_LEVEL) {
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

		for (int iz = 0; iz < GRID; iz++) {
			for (int ix = 0; ix < GRID; ix++) {
				double worldX = minX + (ix + 0.5) * step;
				double worldZ = minZ + (iz + 0.5) * step;
				double height = terrain.heightAt(worldX, worldZ);

				if (height <= WorldBounds.SEA_LEVEL) {
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

		ProbeResult.Builder out = ProbeResult.titled("");

		out.blank();
		out.row("BASINS, over " + land + " land samples");
		out.row("  distinct basins        %,d", extents.size());
		out.row("  largest span         %,.0f blocks   [needs %,.0f margin, has %,.0f]",
				largest, largest * MARGIN_FACTOR, marginBlocks);
		out.row("  largest share          %.1f%% of land in view",
				land == 0 ? 0.0 : 100.0 * largestArea / land);

		// Not compared against Earth's 2 percent. That figure is dominated by glacial
		// lakes, and this world has no ice history yet: the design holds MORAINE and
		// LAKE_LAND back until the glacial overprint exists. Earth's non-glacial lakes
		// are closer to half a percent of land.
		out.row("  lakes                  %.2f%% of land, %.0f%% of them terminal"
						+ "   [non-glacial Earth about 0.5%%]",
				100.0 * lake / land, lake == 0 ? 0.0 : 100.0 * terminal / lake);
		out.row("  playas                 %.2f%% of land   [Earth about 0.3%%]", 100.0 * playa / land);
		out.row("  endorheic              %.1f%% of land drains to no sea   [Earth about 18%%]",
				100.0 * endorheic / land);

		if (largest * MARGIN_FACTOR > marginBlocks) {
			out.row("  *** largest basin needs %,.0f blocks of margin and has "
					+ "%,.0f; two tiles could disagree about it", largest * MARGIN_FACTOR, marginBlocks);
		}

		return out.build();
	}
}
