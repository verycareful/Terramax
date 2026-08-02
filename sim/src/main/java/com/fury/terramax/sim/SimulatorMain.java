package com.fury.terramax.sim;

/**
 * Entry point for the standalone terrain simulator.
 *
 * <p>The simulator exists because Terramax's scale makes in-game iteration
 * impractical: with plate centres roughly 100,000 blocks apart, reaching a plate
 * boundary means flying 50,000 blocks. Rendering the same maths to a PNG takes a
 * second.
 *
 * <p>It links {@code :core} only. If this class ever needs something from
 * {@code :mod}, that is a sign terrain maths has leaked into the mod and belongs
 * in {@code :core} instead.
 */
public final class SimulatorMain {
	private SimulatorMain() {
	}

	public static void main(final String[] args) {
		System.out.println("Terramax terrain simulator");
		System.out.println("Nothing to render yet: :core has no terrain functions.");
		System.out.println("Next: PoissonDisk and VoronoiSolver, then the map renderer.");
	}
}
