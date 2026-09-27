package com.fury.terramax.sim.probe;

import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.TerrainModel;

/**
 * The largest step the surface takes between two adjacent blocks.
 *
 * <p>Walked at one-block spacing because the discontinuity being hunted is exactly one
 * block wide. It sits on the bisector, where the query's own crust cell flips and with
 * it the profile the point is built from, so a walk at any coarser spacing averages it
 * away into an ordinary slope.
 *
 * <p><b>The control is the point of the report.</b> "Worst held step" is the worst
 * jump with no change of margin class, which is ordinary steep ground and the standard
 * a margin crossing has to beat. Reporting a worst crossing without it would leave no
 * way to tell a defect from a mountainside.
 *
 * <p>Ignores the context's view. The transects are laid out in crust spacings around
 * the origin so the walk is the same every run and successive builds are comparable;
 * measuring wherever the map happens to be would make the numbers incomparable, which
 * is the one thing this report cannot afford.
 *
 * <p><b>Being comparable is the whole of its job, and is not the same as being
 * complete.</b> Eight transects around the origin cover a box 20,000 blocks by 82,000
 * in a world that is unbounded, so this reporting 7.4 blocks says the ground near the
 * origin is sound and says nothing at all about the rest. It said exactly that for
 * three commits while an 82-block wall stood at {@code 189,388, -412,767}. Hunting for
 * walls anywhere is {@link WallProbe}'s job; do not widen this one to do it, or the
 * numbers stop being comparable and the regression control is gone.
 */
public final class ReliefContinuityProbe implements Probe {
	private static final int TRANSECTS = 8;
	private static final int TRANSECT_BLOCKS = 20_000;

	/** Spacing between transects, in crust cells. Not a whole number, so they do not
	 * all land on the same phase of the lattice. */
	private static final double TRANSECT_GAP_CELLS = 1.7;

	@Override
	public String name() {
		return "continuity";
	}

	@Override
	public String description() {
		return "worst one-block step in the surface, against a same-class control";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TectonicHeight tectonic = model.snapshot().uplift().tectonic();
		double spacing = model.plateSettings().crustSpacingBlocks();

		double worstStep = 0.0;
		double worstStepAtX = 0.0;
		double worstStepAtZ = 0.0;
		String worstStepClass = "";

		double switchedTotal = 0.0;
		int switchedCount = 0;
		double switchedWorst = 0.0;
		String switchedWorstPair = "";

		double heldTotal = 0.0;
		double heldWorst = 0.0;
		int heldCount = 0;

		for (int transect = 0; transect < TRANSECTS; transect++) {
			double z = (transect - TRANSECTS * 0.5) * spacing * TRANSECT_GAP_CELLS;
			double startX = -TRANSECT_BLOCKS * 0.5;

			TectonicHeight.Sample previous = tectonic.sample(startX, z);

			for (int i = 1; i < TRANSECT_BLOCKS; i++) {
				double x = startX + i;
				TectonicHeight.Sample current = tectonic.sample(x, z);

				double jump = Math.abs(current.height() - previous.height());
				String before = Probes.marginClass(previous);
				String after = Probes.marginClass(current);

				if (before.equals(after)) {
					heldTotal += jump;
					heldCount++;
					heldWorst = Math.max(heldWorst, jump);
				} else {
					switchedTotal += jump;
					switchedCount++;

					if (jump > switchedWorst) {
						switchedWorst = jump;
						switchedWorstPair = before + " -> " + after;
					}
				}

				if (jump > worstStep) {
					worstStep = jump;
					worstStepAtX = x;
					worstStepAtZ = z;
					worstStepClass = before.equals(after) ? after : before + " -> " + after;
				}

				previous = current;
			}
		}

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"  continuity, %d transects x %,d blocks at 1-block steps",
				TRANSECTS, TRANSECT_BLOCKS));

		out.row("    mean step, class held        %7.3f blocks over %,d steps",
				heldCount == 0 ? 0.0 : heldTotal / heldCount, heldCount);
		out.row("    mean step, class switched    %7.3f blocks over %,d steps",
				switchedCount == 0 ? 0.0 : switchedTotal / switchedCount, switchedCount);
		out.row("    worst held step              %7.1f blocks   (the control: ordinary ground)",
				heldWorst);
		out.row("    worst switched step          %7.1f blocks, %s", switchedWorst, switchedWorstPair);
		out.row("    worst step anywhere          %7.1f blocks at %,.0f, %,.0f, %s",
				worstStep, worstStepAtX, worstStepAtZ, worstStepClass);
		out.blank();

		// The worst step is somewhere to look, not just a number. Handing it back as a
		// place means a suspect discontinuity can be rendered without anyone copying
		// coordinates out of a terminal.
		out.place(new Place(
				"worst one-block step, " + worstStepClass, worstStepAtX, worstStepAtZ, 4_000.0));

		return out.build();
	}
}
