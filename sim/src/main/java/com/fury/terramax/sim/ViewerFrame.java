package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * The simulator window: map in the middle, one column of panels beside it, a cross
 * section and a status line underneath.
 *
 * <p><b>The map gets the room now.</b> There used to be a 300-pixel settings column
 * pinned open on the left and a 250-pixel statistics column on the right, so the thing
 * being looked at got whatever was left. Settings moved into the side column and start
 * folded, because tuning a slider is a thing you do occasionally and looking at terrain
 * is the thing you do constantly.
 *
 * <p>The panel that earns its place is {@link MeasurePanel}. Measurements used to live
 * only on the command line, so checking a change meant running a batch in one program
 * and hunting for what it described in another, with no way across. Now a probe runs
 * against the window on screen and anything it locates becomes a button that moves the
 * map there, at the scale that thing is visible at.
 */
public final class ViewerFrame extends JFrame {
	private static final int WINDOW_WIDTH = 1600;
	private static final int WINDOW_HEIGHT = 1000;

	/** Width of the column beside the map. Wide enough for a probe's fixed-width rows. */
	private static final int SIDE_COLUMN_WIDTH = 330;

	/**
	 * Opening view, in blocks across. Sixty-four chunks.
	 *
	 * <p>It used to open on 140 crust cells, which is 840,000 blocks, and render them
	 * at panel resolution before drawing anything at all. Measured at 92 seconds to
	 * first image. Nobody asked for that view and it was the first thing anybody saw.
	 *
	 * <p>A thousand blocks is close to one block per pixel, which is the scale terrain
	 * detail actually exists at, and it is where somebody standing in the world would
	 * be. Zooming out is one gesture away.
	 */
	private static final double INITIAL_SPAN_BLOCKS = 1_024.0;

	private final transient TerrainModel model;
	private final transient MapPanel map;
	private final transient StatisticsPanel statistics = new StatisticsPanel();
	private final transient LegendPanel legend = new LegendPanel();
	private final transient SectionPanel section = new SectionPanel();
	private final transient StatusBar status = new StatusBar();
	private final transient MeasurePanel measure;

	public ViewerFrame(final long seed) {
		super("Terramax terrain simulator");

		this.model = new TerrainModel(seed);
		this.map = new MapPanel(model, new MapEvents(), INITIAL_SPAN_BLOCKS);
		this.measure = new MeasurePanel(model, map::currentView,
				place -> map.goTo(place.worldX(), place.worldZ(), place.spanBlocks()));

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		add(toolbar(), BorderLayout.NORTH);
		add(map, BorderLayout.CENTER);
		add(sideColumn(), BorderLayout.EAST);
		add(bottom(), BorderLayout.SOUTH);

		legend.showLayer(map.layer());

		setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		setLocationRelativeTo(null);
	}

	/**
	 * Everything that is not the map, in one folding column.
	 *
	 * <p>Order is how often you want each. Measure first because it is the reason to
	 * have the viewer open, then what the current view measures out to, then the legend
	 * for the layer, then settings, which start folded.
	 */
	private JScrollPane sideColumn() {
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		stack.add(group("measure", true, measure));
		stack.add(group("statistics", true, statistics));
		stack.add(group("legend", true, legend));
		stack.add(group("settings", false, new ControlPanel(model, this::worldChanged)));
		stack.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(stack);
		scroll.setPreferredSize(new Dimension(SIDE_COLUMN_WIDTH, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		return scroll;
	}

	private static CollapsibleGroup group(
			final String title, final boolean open, final java.awt.Component content) {
		CollapsibleGroup box = new CollapsibleGroup(title, open);
		box.addControl(content);

		return box;
	}

	private JPanel bottom() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(section, BorderLayout.CENTER);
		panel.add(status, BorderLayout.SOUTH);

		return panel;
	}

	private JPanel toolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		bar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

		bar.add(new JLabel("layer"));
		bar.add(layerSelector());

		bar.add(new JLabel("     seed"));
		bar.add(seedSpinner());

		bar.add(new JLabel("     go to"));
		bar.add(gotoField());

		return bar;
	}

	/**
	 * One list of layers, grouped by what they are about.
	 *
	 * <p>The two renderer enums used to be poured into a single combo box and taken
	 * apart again with {@code instanceof}, which put twenty-one entries in declaration
	 * order under the constant names the renderer happens to use. {@link MapLayer}
	 * carries the category, so the list reads as plates, terrain, water, climate, with
	 * an unselectable heading before each.
	 */
	private JComboBox<Object> layerSelector() {
		DefaultComboBoxModel<Object> items = new DefaultComboBoxModel<>();

		for (MapLayer.Category category : MapLayer.Category.values()) {
			items.addElement("-- " + category.label());

			for (MapLayer layer : MapLayer.of(category)) {
				items.addElement(layer);
			}
		}

		JComboBox<Object> box = new JComboBox<>(items);
		box.setSelectedItem(map.layer());

		box.addActionListener(e -> {
			// A heading is not a layer. Selecting one puts the previous choice back,
			// which is less surprising than leaving the box showing a heading while the
			// map shows something else.
			if (box.getSelectedItem() instanceof MapLayer chosen) {
				map.setLayer(chosen);
				legend.showLayer(chosen);
			} else {
				box.setSelectedItem(map.layer());
			}
		});

		return box;
	}

	private JSpinner seedSpinner() {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(
				Long.valueOf(model.seed()),
				Long.valueOf(Long.MIN_VALUE), Long.valueOf(Long.MAX_VALUE), Long.valueOf(1L)));

		spinner.setPreferredSize(new Dimension(90, 24));
		spinner.addChangeListener(e -> {
			model.setSeed(((Number) spinner.getValue()).longValue());
			worldChanged();
		});

		return spinner;
	}

	/** Jump to a coordinate. Panning to x=8,000,000 by dragging is not a plan. */
	private JTextField gotoField() {
		JTextField field = new JTextField("0, 0", 12);

		field.addActionListener(e -> {
			String[] parts = field.getText().split("[,\\s]+");

			if (parts.length < 2) {
				return;
			}

			try {
				map.goTo(
						Double.parseDouble(parts[0].trim().replace(",", "")),
						Double.parseDouble(parts[1].trim().replace(",", "")));
			} catch (NumberFormatException ignored) {
				// Leave the field alone and do nothing. A malformed coordinate is a
				// typo mid-edit, not something to interrupt the user about.
			}
		});

		return field;
	}

	private void worldChanged() {
		map.refresh();
	}

	/** Routes what the map reports to the panels around it. */
	private final class MapEvents implements MapPanel.MapListener {
		@Override
		public void cursorMoved(final double worldX, final double worldZ) {
			status.showPoint(model.snapshot(), worldX, worldZ);
		}

		@Override
		public void cursorLeft() {
			status.showHint();
		}

		@Override
		public void sectionDrawn(
				final double startX, final double startZ, final double endX, final double endZ) {
			section.plot(model.snapshot().terrain(), startX, startZ, endX, endZ);
		}

		@Override
		public void renderComplete(
				final MapView view, final long elapsedMs, final int level, final int levels) {
			status.showRender(model.snapshot(), view, elapsedMs, level, levels);

			// Statistics only at the top of the ladder. Measuring a coarse level would
			// report numbers for a picture that is about to be replaced, and at 96
			// pixels it would report them from 9,216 samples as though they described
			// the view.
			if (level == levels) {
				statistics.update(model.snapshot(), view);
			}
		}
	}

	public static void launch(final long seed) {
		SwingUtilities.invokeLater(() -> new ViewerFrame(seed).setVisible(true));
	}

	private static final long serialVersionUID = 1L;
}
