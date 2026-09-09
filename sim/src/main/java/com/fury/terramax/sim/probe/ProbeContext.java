package com.fury.terramax.sim.probe;

import java.util.List;

import com.fury.terramax.sim.MapView;

/**
 * Where a probe should measure, and what it was asked.
 *
 * <p><b>Carrying the view is the point.</b> Every measurement in the batch renderer
 * built its own window onto the world, hardcoded to the continental span centred on
 * the origin, so a census always described the same 840,000 blocks whatever the person
 * running it was actually looking at. Taking the window as an argument means the
 * command line can pass that same continental view and get the same numbers as before,
 * while the viewer can pass whatever is on screen and measure that instead.
 *
 * <p>Probes that sweep a grid should sample within {@link #view} rather than inventing
 * an extent. Probes given an explicit position, such as a walk along a transect, take
 * it from {@link #args} and may ignore the view.
 *
 * @param view the window to measure over
 * @param args whatever followed the probe's name, unparsed
 */
public record ProbeContext(MapView view, List<String> args) {
	public ProbeContext {
		args = List.copyOf(args);
	}

	public static ProbeContext over(final MapView view, final String... args) {
		return new ProbeContext(view, List.of(args));
	}

	public boolean has(final int index) {
		return index < args.size();
	}

	public String arg(final int index) {
		require(index);

		return args.get(index);
	}

	public String arg(final int index, final String fallback) {
		return has(index) ? args.get(index) : fallback;
	}

	public double doubleArg(final int index) {
		require(index);

		// Commas stripped so a coordinate pasted out of a probe's own output, which
		// prints them grouped, can be handed straight back to another probe.
		try {
			return Double.parseDouble(args.get(index).replace(",", "").trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"argument " + index + " must be a number, got " + args.get(index), e);
		}
	}

	public int intArg(final int index) {
		return (int) doubleArg(index);
	}

	private void require(final int index) {
		if (!has(index)) {
			throw new IllegalArgumentException(
					"missing argument " + index + "; got " + args.size() + " arguments");
		}
	}
}
