package com.fury.terramax.sim.probe;

import com.fury.terramax.sim.TerrainModel;

/**
 * One measurement of the world, runnable from anywhere.
 *
 * <p>These were methods on the batch renderer that printed to standard output as they
 * went, which had two consequences worth undoing. The viewer could not run them, so
 * verifying a change meant reading numbers in one program and looking at pictures in
 * another with no way to connect the two. And because each was written for the
 * question in front of it, they drifted: one probe measured a range envelope with a
 * linear taper where the generator uses a smoothstep dome, and reported 0.374 where
 * the truth was 0.549. A measurement that disagrees with the thing it measures is
 * worse than no measurement, because it is believed.
 *
 * <p>A probe returns its findings rather than printing them, so the same code answers
 * the command line and the viewer. Anything it discovers a location for it reports as
 * a {@link Place}, which is what lets a measurement lead to a render.
 *
 * <p>Implementations must be pure with respect to the world: they may read anything
 * from the model and must change nothing. Several are run repeatedly while tuning.
 */
public interface Probe {
	/** Name used on the command line and in the viewer's list. Lower case, no spaces. */
	String name();

	/** One line saying what question this answers. Shown when listing probes. */
	String description();

	ProbeResult run(TerrainModel model, ProbeContext context);
}
