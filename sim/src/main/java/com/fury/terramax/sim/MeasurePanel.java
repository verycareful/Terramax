package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.fury.terramax.sim.probe.Place;
import com.fury.terramax.sim.probe.Probe;
import com.fury.terramax.sim.probe.ProbeContext;
import com.fury.terramax.sim.probe.ProbeRegistry;
import com.fury.terramax.sim.probe.ProbeResult;

/**
 * Runs a measurement over what is on screen, and turns what it finds into somewhere to
 * look.
 *
 * <p><b>This is the panel the rebuild exists for.</b> Every probe lived on the command
 * line, so checking a change meant running a batch in one program, reading numbers, and
 * then hunting for the thing they described by panning around in another. There was no
 * way across. A census could say a landform covered nine percent of the world while
 * every render on screen contained none of it, and nothing in either program would say
 * so.
 *
 * <p>Two halves close that gap. Probes measure {@link ProbeContext#view}, so a
 * measurement describes the window you are looking at rather than a fixed continental
 * extent nobody chose. And a probe that finds a location returns a {@link Place},
 * carrying a span as well as coordinates, which becomes a button that moves the map
 * there at a scale where the thing is actually visible. Fault blocks are 1,200 blocks
 * apart: at a continental span they are a pixel and a half wide, so a jump that kept
 * the current zoom would land you on them and show nothing.
 *
 * <p>Probes are slow, some of them tens of seconds, so one runs on its own thread and
 * the panel says which is running. Only one at a time: they all walk the same world and
 * running several would compete for the same cores while making every one of them look
 * slower than it is.
 */
public final class MeasurePanel extends JPanel {
	private static final Color BACKGROUND = new Color(30, 34, 42);
	private static final Color TEXT = new Color(206, 214, 228);
	private static final Color RUNNING = new Color(240, 196, 92);

	private static final int OUTPUT_ROWS = 14;

	private final transient TerrainModel model;
	private final transient ViewSource viewSource;
	private final transient Consumer<Place> onPlaceChosen;

	private final JComboBox<Probe> probes = new JComboBox<>();
	private final JTextField arguments = new JTextField(10);
	private final JButton run = new JButton("run");
	private final JLabel state = new JLabel(" ");
	private final JTextArea output = new JTextArea(OUTPUT_ROWS, 30);
	private final JPanel places = new JPanel();

	/** Where the panel gets the window to measure, without owning the map. */
	@FunctionalInterface
	public interface ViewSource {
		MapView currentView();
	}

	public MeasurePanel(
			final TerrainModel model, final ViewSource viewSource,
			final Consumer<Place> onPlaceChosen) {
		this.model = model;
		this.viewSource = viewSource;
		this.onPlaceChosen = onPlaceChosen;

		setLayout(new BorderLayout());
		setBackground(BACKGROUND);

		for (Probe probe : ProbeRegistry.all()) {
			probes.addItem(probe);
		}

		probes.setRenderer(new ProbeName());
		probes.addActionListener(e -> showDescription());

		run.setFocusPainted(false);
		run.addActionListener(e -> start());

		// Enter in the argument field runs, so "find fault_block" is type and go rather
		// than type and then reach for the mouse.
		arguments.addActionListener(e -> start());

		state.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		state.setForeground(TEXT);

		output.setEditable(false);
		output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		output.setBackground(BACKGROUND);
		output.setForeground(TEXT);
		output.setLineWrap(false);

		places.setLayout(new BoxLayout(places, BoxLayout.Y_AXIS));
		places.setBackground(BACKGROUND);

		add(controls(), BorderLayout.NORTH);
		add(new JScrollPane(output), BorderLayout.CENTER);
		add(new JScrollPane(places), BorderLayout.SOUTH);

		showDescription();
	}

	private JPanel controls() {
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(BACKGROUND);

		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		row.setBackground(BACKGROUND);
		row.add(probes);
		row.add(arguments);
		row.add(run);

		top.add(row, BorderLayout.NORTH);
		top.add(state, BorderLayout.SOUTH);

		return top;
	}

	private void showDescription() {
		Probe probe = (Probe) probes.getSelectedItem();

		if (probe != null) {
			state.setForeground(TEXT);
			state.setText("  " + probe.description());
		}
	}

	private void start() {
		Probe probe = (Probe) probes.getSelectedItem();

		if (probe == null || !run.isEnabled()) {
			return;
		}

		// Read the view on the event thread, before the worker starts. Reading it from
		// the worker would race with a pan and could measure a window that was never on
		// screen.
		MapView view = viewSource.currentView();
		String[] args = arguments.getText().isBlank()
				? new String[0]
				: arguments.getText().trim().split("[,\\s]+");

		run.setEnabled(false);
		state.setForeground(RUNNING);
		state.setText("  running " + probe.name() + "...");
		places.removeAll();
		places.revalidate();

		Thread worker = new Thread(() -> {
			ProbeResult result;

			try {
				result = probe.run(model, ProbeContext.over(view, args));
			} catch (RuntimeException e) {
				// Shown rather than thrown. A bad argument is the common case, and
				// losing the message into a stack trace on a background thread would
				// leave the panel looking hung.
				result = ProbeResult.titled(probe.name() + " failed")
						.row(String.valueOf(e.getMessage()))
						.build();
			}

			ProbeResult finished = result;

			SwingUtilities.invokeLater(() -> finish(finished));
		}, "terramax-probe");

		worker.setDaemon(true);
		worker.start();
	}

	private void finish(final ProbeResult result) {
		StringBuilder text = new StringBuilder(result.title());

		for (String row : result.rows()) {
			text.append('\n').append(row);
		}

		output.setText(text.toString());
		output.setCaretPosition(0);

		places.removeAll();

		for (Place place : result.places()) {
			places.add(placeButton(place));
		}

		places.revalidate();
		places.repaint();

		run.setEnabled(true);
		showDescription();
	}

	private JButton placeButton(final Place place) {
		JButton button = new JButton(String.format(
				"%s   at %,.0f, %,.0f", place.label(), place.worldX(), place.worldZ()));

		button.setFocusPainted(false);
		button.setHorizontalAlignment(JButton.LEFT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.addActionListener(e -> onPlaceChosen.accept(place));

		return button;
	}

	/** Shows a probe by name, since its {@code toString} is the class name. */
	private static final class ProbeName extends javax.swing.DefaultListCellRenderer {
		@Override
		public java.awt.Component getListCellRendererComponent(
				final javax.swing.JList<?> list, final Object value, final int index,
				final boolean selected, final boolean focused) {
			super.getListCellRendererComponent(list, value, index, selected, focused);

			if (value instanceof Probe probe) {
				setText(probe.name());
			}

			return this;
		}

		private static final long serialVersionUID = 1L;
	}

	private static final long serialVersionUID = 1L;
}
