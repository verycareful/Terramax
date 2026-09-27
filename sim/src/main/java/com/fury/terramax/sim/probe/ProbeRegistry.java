package com.fury.terramax.sim.probe;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Every measurement the simulator can take, by name.
 *
 * <p>One list, so a probe written once is available everywhere. The command line looks
 * a name up here and so does the viewer, which is the whole point: the alternative,
 * which this replaces, was a chain of flag comparisons in the batch renderer calling
 * private methods that printed as they went. Nothing outside that file could run them,
 * so verifying a terrain change meant reading numbers in one program and looking at
 * pictures in another, with no way to get from one to the other.
 *
 * <p>Insertion ordered, so listing them reads in a sensible order rather than
 * alphabetically or by hash.
 */
public final class ProbeRegistry {
	private static final Map<String, Probe> PROBES = index(
			new MarginCensusProbe(),
			new RangeEnvelopeProbe(),
			new ReliefProfileProbe(),
			new ReliefContinuityProbe(),
			new HotspotProbe(),
			new FindRangeTypeProbe(),
			new MarginWalkProbe(),
			new BasinProbe(),
			new ChunkCostProbe(),
			new DrainageCostProbe());

	/**
	 * The measurements that describe range shape, in the order they read best.
	 *
	 * <p>Named as a group because they answer one question between them and are
	 * meaningless apart: a census says what the world is made of, the envelope says
	 * whether ranges are allowed their full height, the profile says what shape they
	 * come out, and continuity says whether the result is a surface or a set of walls.
	 */
	public static final List<String> RANGE_SUITE =
			List.of("census", "envelope", "profile", "continuity");

	private ProbeRegistry() {
	}

	private static Map<String, Probe> index(final Probe... probes) {
		Map<String, Probe> map = new LinkedHashMap<>();

		for (Probe probe : probes) {
			if (map.put(probe.name(), probe) != null) {
				throw new IllegalStateException("two probes named " + probe.name());
			}
		}

		return map;
	}

	/** Throws with the available names rather than returning null, since a typo is common. */
	public static Probe get(final String name) {
		Probe probe = PROBES.get(name);

		if (probe == null) {
			throw new IllegalArgumentException(
					"no probe named " + name + "; available: " + String.join(", ", PROBES.keySet()));
		}

		return probe;
	}

	public static Collection<Probe> all() {
		return PROBES.values();
	}
}
