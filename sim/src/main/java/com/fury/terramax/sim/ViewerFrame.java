package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * The simulator window: controls left, map centre, statistics and legend right,
 * cross section docked below, status bar underneath.
 *
 * <p>Everything is visible at once because the tuning loop needs it to be. The loop
 * is: move a slider, watch the map, check whether the numbers moved the right way.
 * Hiding the numbers behind a tab, which the previous layout effectively did by only
 * printing them to the console, breaks the third step.
 */
public final class ViewerFrame extends JFrame {
	private static final int WINDOW_WIDTH = 1600;
	private static final int WINDOW_HEIGHT = 1000;

	/** Opening view, in crust cells across. Wide enough to hold several plates. */
	private static final double INITIAL_SPAN_CELLS = 140.0;

	private final transient TerrainModel model;
	private final transient MapPanel map;
	private final transient StatisticsPanel statistics = new StatisticsPanel();
	private final transient LegendPanel legend = new LegendPanel();
	private final transient SectionPanel section = new SectionPanel();
	private final transient StatusBar status = new StatusBar();

	private transient boolean lastRenderWasDraft;

	public ViewerFrame(final long seed) {
		super("Terramax terrain simulator");

		this.model = new TerrainModel(seed);
		this.map = new MapPanel(model, new MapEvents(),
				model.plateSettings().crustSpacingBlocks() * INITIAL_SPAN_CELLS);

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		add(toolbar(), BorderLayout.NORTH);
		add(new ControlPanel(model, this::worldChanged), BorderLayout.WEST);
		add(map, BorderLayout.CENTER);
		add(rightColumn(), BorderLayout.EAST);
		add(bottom(), BorderLayout.SOUTH);

		legend.showTerrainLayer(map.terrainLayer());

		setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		setLocationRelativeTo(null);
	}

	private JPanel rightColumn() {
		JPanel column = new JPanel(new BorderLayout());
		column.setPreferredSize(new Dimension(250, 0));
		column.add(statistics, BorderLayout.CENTER);
		column.add(legend, BorderLayout.SOUTH);

		return column;
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

		ButtonGroup modes = new ButtonGroup();

		for (ViewerMode mode : ViewerMode.values()) {
			JToggleButton button = new JToggleButton(mode.label());
			button.setFocusPainted(false);
			button.setSelected(mode == ViewerMode.PAN);
			button.addActionListener(e -> {
				map.setMode(mode);
				status.showHint(mode);

				if (mode != ViewerMode.SECTION) {
					section.clear();
				}
			});

			modes.add(button);
			bar.add(button);
		}

		bar.add(new JLabel("     layer"));
		bar.add(layerSelector());

		bar.add(new JLabel("     seed"));
		bar.add(seedSpinner());

		bar.add(new JLabel("     go to"));
		bar.add(gotoField());

		return bar;
	}

	/**
	 * One selector over both layer families.
	 *
	 * <p>Two separate combo boxes would let the user pick a plate layer and a terrain
	 * layer at once, and then show neither of the two they chose.
	 */
	private JComboBox<Object> layerSelector() {
		DefaultComboBoxModel<Object> items = new DefaultComboBoxModel<>();

		for (MapRenderer.TerrainLayer layer : MapRenderer.TerrainLayer.values()) {
			items.addElement(layer);
		}

		for (MapRenderer.Layer layer : MapRenderer.Layer.values()) {
			items.addElement(layer);
		}

		JComboBox<Object> box = new JComboBox<>(items);
		box.setSelectedItem(map.terrainLayer());

		box.addActionListener(e -> {
			Object selected = box.getSelectedItem();

			if (selected instanceof MapRenderer.TerrainLayer terrain) {
				map.setTerrainLayer(terrain);
				legend.showTerrainLayer(terrain);
			} else if (selected instanceof MapRenderer.Layer plate) {
				map.setLayer(plate);
				legend.showPlateLayer(plate);
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
			status.showHint(map.mode());
		}

		@Override
		public void sectionDrawn(
				final double startX, final double startZ, final double endX, final double endZ) {
			section.plot(model.snapshot().terrain(), startX, startZ, endX, endZ);
		}

		@Override
		public void pointProbed(final double worldX, final double worldZ) {
			// The status bar already reports everything a probe would, live under the
			// cursor. Centring on the point is the useful extra: it makes a probe a
			// way to recentre precisely rather than a redundant readout.
			map.goTo(worldX, worldZ);
		}

		@Override
		public void renderComplete(final MapView view, final long elapsedMs) {
			lastRenderWasDraft = view.pixels() < Math.min(map.getWidth(), map.getHeight());

			status.showRender(view, elapsedMs, lastRenderWasDraft);

			// Statistics only for finished frames. Measuring a draft would report
			// numbers for a view the user is still moving.
			if (!lastRenderWasDraft) {
				statistics.update(model.snapshot(), view);
			}
		}
	}

	public static void launch(final long seed) {
		SwingUtilities.invokeLater(() -> new ViewerFrame(seed).setVisible(true));
	}

	private static final long serialVersionUID = 1L;
}
