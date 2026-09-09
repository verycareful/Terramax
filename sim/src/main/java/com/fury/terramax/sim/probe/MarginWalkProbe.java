package com.fury.terramax.sim.probe;

import java.util.ArrayList;
import java.util.List;

import com.fury.terramax.core.terrain.RangeType;
import com.fury.terramax.sim.TerrainModel;

/**
 * Every margin in reach of a walk, one column at a time, with what each contributes.
 *
 * <p>Relief is an average over margins, so when it misbehaves the question is always
 * which margins were in the average and what each one weighed. A height cannot answer
 * that and neither can a section. This prints the terms.
 *
 * <p>It is what found the second attempt at the combination formula to be wrong: an
 * average is scale invariant, so a margin whose weight had fallen to 0.0004 still
 * yielded its full profile and then vanished, and the dump showed it contributing
 * 1,248 blocks at a printed weight of 0.000.
 *
 * <p>Takes {@code x z steps} and walks along x. Ignores the context's view.
 */
public final class MarginWalkProbe implements Probe {
	@Override
	public String name() {
		return "margins";
	}

	@Override
	public String description() {
		return "every margin in reach along a walk, with weights; takes x z steps";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		double worldX = context.doubleArg(0);
		double worldZ = context.doubleArg(1);
		int steps = context.intArg(2);

		TerrainModel.Snapshot world = model.snapshot();
		var plates = world.plates();

		// The generator's own classifier, not RangeType.of. Two types are decided
		// against region fields this object holds, so asking it is the only way a probe
		// names the same landform the world builds.
		var ridge = world.uplift().tectonic().ridge();
		double spacing = model.plateSettings().crustSpacingBlocks();
		double rangeWidth = model.terrainSettings().rangeWidthBlocks(spacing);
		double blendWidth = model.terrainSettings().blendWidthBlocks(spacing);

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"MARGINS along x from %,.0f for %,d blocks at z=%,.0f", worldX, steps, worldZ));

		out.row("  %10s %6s %8s   %s", "x", "count", "sum f.w", "each: type across weight");

		for (int i = 0; i < steps; i++) {
			double x = worldX + i;

			List<String> found = new ArrayList<>();
			double[] total = new double[1];

			plates.forEachBoundary(x, worldZ, rangeWidth, blendWidth, boundary -> {
				RangeType type = ridge.rangeType(boundary);

				// The type's own reach, not the shared half-width. Sutures and fault
				// blocks do not use the same one, and a probe that assumed they did
				// would report weight for ground the margin has already stopped
				// building on.
				double falloff = Probes.dome(boundary.boundaryDistance(), ridge.reach(type));

				total[0] += falloff * boundary.weight();
				found.add(String.format("%s %,.0f w%.3f",
						type.name().charAt(0) + type.name().substring(1, 4).toLowerCase(),
						boundary.across(), boundary.weight()));
			});

			out.row("  %,10.0f %6d %8.4f   %s",
					x, found.size(), total[0], String.join(" | ", found));
		}

		return out.build();
	}
}
