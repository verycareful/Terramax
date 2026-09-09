package com.fury.terramax.sim.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a probe found: a title, lines of text, and any places worth rendering.
 *
 * <p>Text rather than structured values, deliberately. These measurements are read by
 * a person deciding whether terrain looks right, and their formatting carries meaning:
 * a relief profile is a row of numbers whose shape is the answer, and turning it into
 * a list of doubles for a caller to reformat would lose the one property that makes it
 * legible. A probe that later needs machine-readable output can add a typed accessor
 * beside these rows rather than instead of them.
 *
 * @param title  one line naming the measurement, printed above the rows
 * @param rows   the measurement, already formatted, without trailing newlines
 * @param places anywhere the probe found worth looking at; usually empty
 */
public record ProbeResult(String title, List<String> rows, List<Place> places) {
	public ProbeResult {
		rows = List.copyOf(rows);
		places = List.copyOf(places);
	}

	public static Builder titled(final String title) {
		return new Builder(title);
	}

	/**
	 * Prints the title and every row to standard output, in order.
	 *
	 * <p>An empty title prints nothing rather than a blank line. Some measurements open
	 * with their own spacing and repeat their whole block several times, so they carry
	 * no single heading; forcing one on them would insert a stray line exactly where
	 * output is being compared against a previous run.
	 */
	public void print() {
		if (!title.isEmpty()) {
			System.out.println(title);
		}

		for (String row : rows) {
			System.out.println(row);
		}
	}

	/**
	 * Collects rows without every probe growing its own list handling.
	 *
	 * <p>{@link #row} takes a format string and arguments so converting a body of
	 * {@code printf} calls is mechanical. It takes no trailing {@code %n}: the row is a
	 * line, and whoever displays it decides how lines are separated. A viewer putting
	 * these in a text pane wants them without embedded newlines.
	 */
	public static final class Builder {
		private final String title;
		private final List<String> rows = new ArrayList<>();
		private final List<Place> places = new ArrayList<>();

		private Builder(final String title) {
			this.title = title;
		}

		public Builder row(final String format, final Object... args) {
			rows.add(args.length == 0 ? format : String.format(format, args));

			return this;
		}

		/** A blank line, for separating blocks within one result. */
		public Builder blank() {
			rows.add("");

			return this;
		}

		public Builder place(final Place place) {
			places.add(place);

			return this;
		}

		public ProbeResult build() {
			return new ProbeResult(title, Collections.unmodifiableList(rows),
					Collections.unmodifiableList(places));
		}
	}
}
