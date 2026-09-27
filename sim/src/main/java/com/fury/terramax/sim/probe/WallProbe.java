package com.fury.terramax.sim.probe;

import java.util.ArrayList;
import java.util.List;

import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/**
 * Hunts one-block walls across a whole view, rather than checking one place for them.
 *
 * <p><b>This exists because {@link ReliefContinuityProbe} could not have found the
 * defect it was written to find.</b> That probe walks eight fixed transects around the
 * origin, which is right for comparing one build against the next and useless for
 * asking whether the world contains a wall: it reported 7.4 blocks as the worst step
 * anywhere for three commits while an 82-block one stood 400,000 blocks away. Both were
 * true statements about the ground each had walked.
 *
 * <p><b>The trick that makes a wide search affordable is that a wall is a
 * discontinuity, and a discontinuity does not average away.</b> Sampling ordinary
 * ground every 16 blocks gives jumps of a few blocks, because the surface has a slope
 * and a slope integrates. Sampling across an 82-block wall every 16 blocks still gives
 * 82, because the drop happens between two adjacent columns no matter how far apart
 * the samples either side of it are. So a coarse sweep sees every wall it crosses, at
 * a sixteenth of the cost, and the only thing it cannot do is say the wall is one block
 * wide. That is what the refinement pass is for: each suspect is re-walked at one-block
 * spacing, which both confirms it and locates it exactly.
 *
 * <p>Transects run along x and along z alike, because the locus a wall lies on is a
 * curve and a set of parallel lines is blind to any curve running parallel to them.
 *
 * <p><b>A wall is one large step among small ones, not one large step.</b> The first
 * build of this reported a 28-block step as its worst find, and the ground there turned
 * out to rise 28 blocks in <i>every</i> block for as far as it was walked: the flank of
 * a collision range at the stale range width, which is a wall to walk up and not a
 * defect in the surface. So each refined interval is reported with the mean of its
 * other steps beside the worst one, and only a step that stands well clear of its own
 * neighbourhood is called a wall. That ratio is the same control
 * {@link ReliefContinuityProbe} gets from holding the margin class constant, arrived at
 * from the other direction.
 *
 * <p><b>It samples, and cannot prove absence.</b> This was established the hard way:
 * run against a build with a known 82-block wall in it, the default density found the
 * same thirty walls it finds without one and missed the wall entirely. At the default
 * the transects are 35,000 blocks apart, and the locus of a wall is a curve that may be
 * shorter than that in every direction. A silent report means no wall was crossed, not
 * that none exists, which is why the report always prints the spacing it used and why
 * the count is an argument. Raising it is linear in cost and the only thing that makes
 * a quiet answer worth anything.
 */
public final class WallProbe implements Probe {
	/**
	 * Transects per axis by default, doubled because the same number run the other way.
	 *
	 * <p>A compromise that finds a wall on a long locus in about a minute. Twenty four
	 * over a continental view puts them 35,000 blocks apart, which is coarse against
	 * the features being hunted; pass a larger number as the probe's first argument when
	 * the question is whether the world is clean rather than whether a known place is.
	 */
	private static final int TRANSECTS = 24;

	/**
	 * Blocks between samples on the coarse sweep.
	 *
	 * <p>Sixteen, so the sweep costs a sixteenth of a full walk. The upper bound on
	 * this is set by how steep ordinary ground can be: at 7.4 blocks of rise per block,
	 * sixteen blocks of ordinary mountainside can climb 118, which would drown a wall
	 * of 82 in the candidate list. The refinement pass is what keeps that from
	 * mattering, since ordinary ground refines to a slope and a wall refines to a wall,
	 * but the list still has to be long enough to hold the wall among the mountainsides.
	 */
	private static final int STRIDE = 16;

	/**
	 * Coarse jumps carried into the refinement pass.
	 *
	 * <p>Generous, because refinement is cheap: a candidate costs {@code STRIDE}
	 * samples against the sweep's own million. The cost of this being too small is a
	 * wall that never gets looked at, which is the failure this probe exists to end.
	 */
	private static final int CANDIDATES = 200;

	/**
	 * Smallest one-block step worth reporting as a wall.
	 *
	 * <p>Above the 7.4-block control the fixed transects measure on ordinary
	 * mountainside, and well above it, so that what this lists is walls rather than
	 * steep ground. A wall does not need a margin of judgement: the ones found so far
	 * were 36, 79 and 82 blocks against a world whose steepest legitimate ground moves
	 * 7.4.
	 */
	private static final double WALL_BLOCKS = 15.0;

	/**
	 * How far a step must stand above the ground around it to count as a wall.
	 *
	 * <p>Four times the mean of the other steps in its own interval. Ordinary ground,
	 * however steep, spreads its rise evenly and comes out near one; the walls found so
	 * far ran from eighteen to over a thousand, because the ground either side of a
	 * discontinuity is doing nothing at all.
	 */
	private static final double WALL_RATIO = 4.0;

	/** Walls listed. Enough to tell one locus from several. */
	private static final int REPORT_LIMIT = 10;

	@Override
	public String name() {
		return "walls";
	}

	@Override
	public String description() {
		return "hunts one-block walls across the whole view; takes a transect count,"
				+ " default " + TRANSECTS;
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TectonicHeight tectonic = model.snapshot().uplift().tectonic();

		MapView view = context.view();
		double span = view.spanBlocks();
		double originX = view.centreX() - span * 0.5;
		double originZ = view.centreZ() - span * 0.5;

		int transects = context.has(0) ? context.intArg(0) : TRANSECTS;

		List<double[]> candidates = new ArrayList<>();
		int samples = 0;

		for (int transect = 0; transect < transects; transect++) {
			double offset = (transect + 0.5) / transects * span;

			samples += sweep(tectonic, originX, originZ + offset, span, true, candidates);
			samples += sweep(tectonic, originX + offset, originZ, span, false, candidates);
		}

		// Worst first, so a list truncated to CANDIDATES keeps the jumps most likely to
		// be walls rather than whichever the sweep happened to reach first.
		candidates.sort((a, b) -> Double.compare(b[0], a[0]));

		List<double[]> walls = new ArrayList<>();
		double worst = 0.0;

		// Scaled with the sweep, so raising the density raises what gets looked at
		// rather than spreading the same fixed budget over more ground.
		int refined = Math.min(CANDIDATES * transects / TRANSECTS, candidates.size());

		for (int i = 0; i < refined; i++) {
			double[] found = refine(tectonic, candidates.get(i));

			worst = Math.max(worst, found[0]);

			if (found[0] >= WALL_BLOCKS && found[0] >= WALL_RATIO * found[3]) {
				walls.add(found);
			}
		}

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"  wall hunt, %d transects each way over %,.0f blocks, %,d samples at %d-block stride",
				transects, span, samples, STRIDE));

		// Printed every run, silent or not. This probe samples, so what it covers is
		// part of its answer: a clean report at 35,000-block spacing rules out far less
		// than a clean report at 3,500, and the two are otherwise indistinguishable.
		out.row("    transects %,.0f blocks apart, %,d candidates refined at 1 block",
				span / transects, refined);

		if (walls.isEmpty()) {
			out.row("    no one-block step of %.0f blocks or more.", WALL_BLOCKS);
			out.row("    worst refined step %.1f blocks, and it was ordinary slope."
					+ " Nothing was crossed, which is not the same as nothing existing.",
					worst);
			out.blank();

			return out.build();
		}

		walls.sort((a, b) -> Double.compare(b[0], a[0]));

		out.row("    %d walls: a step of %.0f blocks or more, and %.0fx its neighbours",
				walls.size(), WALL_BLOCKS, WALL_RATIO);
		out.row("    %8s %12s %12s %10s", "step", "x", "z", "neighbours");

		for (int i = 0; i < Math.min(REPORT_LIMIT, walls.size()); i++) {
			double[] wall = walls.get(i);

			out.row("    %8.1f %,12.0f %,12.0f %10.2f", wall[0], wall[1], wall[2], wall[3]);

			if (i < REPORT_LIMIT) {
				out.place(new Place(
						String.format("%.0f-block wall", wall[0]), wall[1], wall[2], 4_000.0));
			}
		}

		if (walls.size() > REPORT_LIMIT) {
			out.row("    %d more not listed", walls.size() - REPORT_LIMIT);
		}

		out.blank();

		return out.build();
	}

	/**
	 * Walks one transect at the coarse stride, recording every jump as a candidate.
	 *
	 * <p>Records the jump rather than judging it. Deciding here what is large enough to
	 * be worth refining would need a threshold that holds on ocean floor and on a
	 * mountainside alike, and there is no such number; ranking the whole set and
	 * refining the top of it needs no threshold at all.
	 *
	 * @param alongX true to walk along x at fixed z, false the other way
	 * @return samples taken
	 */
	private int sweep(
			final TectonicHeight tectonic,
			final double startX, final double startZ, final double span,
			final boolean alongX, final List<double[]> into) {
		int steps = (int) (span / STRIDE);
		double previous = tectonic.sample(startX, startZ).height();

		for (int i = 1; i <= steps; i++) {
			double x = alongX ? startX + i * STRIDE : startX;
			double z = alongX ? startZ : startZ + i * STRIDE;
			double height = tectonic.sample(x, z).height();

			into.add(new double[] {
					Math.abs(height - previous), x, z, alongX ? 1.0 : 0.0});

			previous = height;
		}

		return steps + 1;
	}

	/**
	 * Re-walks one coarse interval at one-block spacing.
	 *
	 * <p>Both confirms and locates. Ordinary ground spreads its coarse jump over every
	 * block of the interval and refines to a fraction of it; a wall keeps all of it in
	 * one step and refines to itself, at a coordinate exact enough to render.
	 *
	 * @param candidate jump, end x, end z, and whether the walk ran along x
	 * @return worst one-block step, the x and z it lands on, and the mean of every
	 *         other step in the interval, which is what says whether the worst one is a
	 *         wall or just the steepest block of a steep slope
	 */
	private double[] refine(final TectonicHeight tectonic, final double[] candidate) {
		boolean alongX = candidate[3] != 0.0;
		double endX = candidate[1];
		double endZ = candidate[2];

		double startX = alongX ? endX - STRIDE : endX;
		double startZ = alongX ? endZ : endZ - STRIDE;

		double previous = tectonic.sample(startX, startZ).height();
		double worst = 0.0;
		double worstX = startX;
		double worstZ = startZ;
		double total = 0.0;

		for (int i = 1; i <= STRIDE; i++) {
			double x = alongX ? startX + i : startX;
			double z = alongX ? startZ : startZ + i;
			double height = tectonic.sample(x, z).height();
			double jump = Math.abs(height - previous);

			total += jump;

			if (jump > worst) {
				worst = jump;
				worstX = x;
				worstZ = z;
			}

			previous = height;
		}

		// Everything but the worst step, which is the neighbourhood the worst step has
		// to stand out from. Including it would let a large enough wall raise its own
		// baseline and hide behind it.
		double others = STRIDE > 1 ? (total - worst) / (STRIDE - 1) : 0.0;

		return new double[] {worst, worstX, worstZ, others};
	}
}
