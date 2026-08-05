package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateMapSettings;

/**
 * Interactive window for tuning the plate system.
 *
 * <p>Exists because the world's scale makes iterative tuning impractical. Editing
 * a constant, rebuilding and reading a PNG is a workable loop for checking
 * correctness, but a hopeless one for finding a value that looks right. Sliders
 * make that a matter of seconds.
 *
 * <p>Drag to pan, scroll to zoom about the cursor. Every control rebuilds the
 * {@link PlateMap} and re-renders.
 */
public final class TerrainViewer extends JFrame {
	private static final int WINDOW_WIDTH = 1280;
	private static final int WINDOW_HEIGHT = 900;
	private static final int CONTROL_WIDTH = 300;

	/** Sliders work in integers, so fractional settings are scaled by this. */
	private static final int PERCENT_SCALE = 100;

	private final transient ViewerPanel viewer;

	private long seed;
	private double crustSpacingBlocks;
	private double continentalFraction;
	private double warpStrengthFraction;
	private double transformDominance;
	private double continentWavelengthFactor;

	public TerrainViewer(final long initialSeed, final PlateMapSettings initial) {
		super("Terramax terrain simulator");

		this.seed = initialSeed;
		this.crustSpacingBlocks = initial.crustSpacingBlocks();
		this.continentalFraction = initial.continentalFraction();
		this.warpStrengthFraction = initial.warp().strengthFraction();
		this.transformDominance = initial.transformDominance();
		this.continentWavelengthFactor = initial.continentWavelengthFactor();

		this.viewer = new ViewerPanel(this::buildPlateMap, crustSpacingBlocks * 140.0);

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLayout(new BorderLayout());
		add(viewer, BorderLayout.CENTER);
		add(buildControls(), BorderLayout.EAST);
		setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		setLocationRelativeTo(null);
	}

	/**
	 * Rebuilt on every change rather than mutated.
	 *
	 * <p>{@link PlateMap} calibrates its land threshold at construction, so a
	 * mutable settings object would leave that calibration stale and silently wrong.
	 */
	private PlateMap buildPlateMap() {
		PlateMapSettings settings = new PlateMapSettings(
				crustSpacingBlocks,
				PlateMapSettings.defaults().jitter(),
				continentalFraction,
				PlateMapSettings.defaults().seaLevel(),
				PlateMapSettings.defaults().continentalBase(),
				PlateMapSettings.defaults().oceanicBase(),
				PlateMapSettings.defaults().baseVariation(),
				transformDominance,
				new PlateMapSettings.Warp(
						warpStrengthFraction,
						PlateMapSettings.defaults().warp().wavelengthFactor(),
						PlateMapSettings.defaults().warp().octaves()),
				continentWavelengthFactor);

		return new PlateMap(seed, settings);
	}

	private JPanel buildControls() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		panel.setPreferredSize(new Dimension(CONTROL_WIDTH, WINDOW_HEIGHT));

		panel.add(layerSelector());
		panel.add(seedSpinner());

		panel.add(slider("crust spacing", 2_000, 20_000, (int) crustSpacingBlocks,
				value -> {
					crustSpacingBlocks = value;
					return String.format("%,d blocks", value);
				}));

		panel.add(slider("continental fraction", 0, PERCENT_SCALE,
				(int) Math.round(continentalFraction * PERCENT_SCALE),
				value -> {
					continentalFraction = value / (double) PERCENT_SCALE;
					return value + "%";
				}));

		panel.add(slider("warp strength", 0, 600,
				(int) Math.round(warpStrengthFraction * PERCENT_SCALE),
				value -> {
					warpStrengthFraction = value / (double) PERCENT_SCALE;
					return String.format("%d%% of spacing (%,.0f blocks)",
							value, crustSpacingBlocks * warpStrengthFraction);
				}));

		panel.add(slider("transform dominance", PERCENT_SCALE, 8 * PERCENT_SCALE,
				(int) Math.round(transformDominance * PERCENT_SCALE),
				value -> {
					transformDominance = value / (double) PERCENT_SCALE;
					return String.format("k = %.2f", transformDominance);
				}));

		panel.add(slider("continent wavelength", PERCENT_SCALE, 80 * PERCENT_SCALE,
				(int) Math.round(continentWavelengthFactor * PERCENT_SCALE),
				value -> {
					continentWavelengthFactor = value / (double) PERCENT_SCALE;
					return String.format("%.1f plates", continentWavelengthFactor);
				}));

		panel.add(new JLabel("<html><br>drag to pan<br>scroll to zoom</html>"));

		return panel;
	}

	private JPanel layerSelector() {
		JComboBox<MapRenderer.Layer> box = new JComboBox<>(MapRenderer.Layer.values());
		box.setSelectedItem(viewer.layer());
		box.addActionListener(e -> viewer.setLayer((MapRenderer.Layer) box.getSelectedItem()));

		return labelled("layer", box);
	}

	private JPanel seedSpinner() {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(Long.valueOf(seed),
				Long.valueOf(Long.MIN_VALUE), Long.valueOf(Long.MAX_VALUE), Long.valueOf(1L)));
		spinner.addChangeListener(e -> {
			seed = ((Number) spinner.getValue()).longValue();
			viewer.refresh();
		});

		return labelled("seed", spinner);
	}

	/** A slider with a live readout. {@code formatter} applies the value and labels it. */
	private JPanel slider(
			final String name, final int min, final int max, final int initial,
			final java.util.function.IntFunction<String> formatter) {
		JLabel readout = new JLabel(formatter.apply(initial));

		JSlider control = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
		control.addChangeListener(e -> {
			readout.setText(formatter.apply(control.getValue()));

			// Only re-render once the drag settles, since a full render is not free.
			if (!control.getValueIsAdjusting()) {
				viewer.refresh();
			}
		});

		JPanel panel = new JPanel(new GridLayout(3, 1));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		panel.add(new JLabel(name));
		panel.add(control);
		panel.add(readout);

		return panel;
	}

	private static JPanel labelled(final String name, final java.awt.Component component) {
		JPanel panel = new JPanel(new GridLayout(2, 1));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		panel.add(new JLabel(name));
		panel.add(component);

		return panel;
	}

	public static void launch(final long seed, final PlateMapSettings settings) {
		SwingUtilities.invokeLater(() -> new TerrainViewer(seed, settings).setVisible(true));
	}

	private static final long serialVersionUID = 1L;
}
