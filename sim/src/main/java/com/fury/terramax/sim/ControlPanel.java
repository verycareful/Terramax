package com.fury.terramax.sim;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.function.IntFunction;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;

import com.fury.terramax.core.plate.PlateMapSettings;
import com.fury.terramax.core.region.RegionSettings;

/**
 * The left column: every tunable number, grouped by the subsystem it belongs to.
 *
 * <p>Grouped rather than stacked because the settings now span three lattices and the
 * terrain layer, and a flat list gives no clue which slider affects which. Each group
 * folds, so the subsystem being tuned is open and the rest are out of the way.
 *
 * <p>Settings are pushed into {@link TerrainModel}, which rebuilds the world once.
 * Sliders only fire on release, since a full rebuild plus render is not free and
 * dragging would queue one per pixel of travel.
 */
public final class ControlPanel extends JPanel {
	/** Sliders work in integers, so fractional settings are scaled by this. */
	private static final int PERCENT_SCALE = 100;

	private static final int COLUMN_WIDTH = 300;

	private final transient TerrainModel model;
	private final transient Runnable onChange;

	public ControlPanel(final TerrainModel model, final Runnable onChange) {
		this.model = model;
		this.onChange = onChange;

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		stack.add(plateGroup());
		stack.add(nucleiGroup());
		stack.add(crustTypeGroup());
		stack.add(regionGroup());
		stack.add(javax.swing.Box.createVerticalGlue());

		setLayout(new java.awt.BorderLayout());
		add(new JScrollPane(stack), java.awt.BorderLayout.CENTER);
		setPreferredSize(new Dimension(COLUMN_WIDTH, 0));
	}

	private CollapsibleGroup plateGroup() {
		CollapsibleGroup group = new CollapsibleGroup("crust lattice", true);

		group.addControl(slider("spacing", 2_000, 20_000,
				(int) model.plateSettings().crustSpacingBlocks(),
				value -> {
					plates(p -> new PlateMapSettings(
							value, p.nucleiSpacingBlocks(), p.nucleiMaxWeightFactor(), p.jitter(),
							p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), p.transformDominance(),
							p.warp(), p.continentWavelengthFactor()));

					return String.format("%,d blocks", value);
				}));

		group.addControl(slider("warp strength", 0, 600,
				(int) Math.round(model.plateSettings().warp().strengthFraction() * PERCENT_SCALE),
				value -> {
					double fraction = value / (double) PERCENT_SCALE;

					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), p.nucleiSpacingBlocks(), p.nucleiMaxWeightFactor(),
							p.jitter(), p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), p.transformDominance(),
							new PlateMapSettings.Warp(
									fraction, p.warp().wavelengthFactor(), p.warp().octaves()),
							p.continentWavelengthFactor()));

					return String.format("%.2fx spacing (%,.0f blocks)",
							fraction, model.plateSettings().crustSpacingBlocks() * fraction);
				}));

		return group;
	}

	private CollapsibleGroup nucleiGroup() {
		CollapsibleGroup group = new CollapsibleGroup("plate nuclei", true);

		group.addControl(slider("spacing", 10_000, 200_000,
				(int) model.plateSettings().nucleiSpacingBlocks(),
				value -> {
					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), value, p.nucleiMaxWeightFactor(), p.jitter(),
							p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), p.transformDominance(),
							p.warp(), p.continentWavelengthFactor()));

					return String.format("%,d blocks", value);
				}));

		group.addControl(slider("size weight", 0, 3 * PERCENT_SCALE,
				(int) Math.round(model.plateSettings().nucleiMaxWeightFactor() * PERCENT_SCALE),
				value -> {
					double factor = value / (double) PERCENT_SCALE;

					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), p.nucleiSpacingBlocks(), factor, p.jitter(),
							p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), p.transformDominance(),
							p.warp(), p.continentWavelengthFactor()));

					return String.format("%.2fx spacing, %dx%d search",
							factor,
							2 * (2 + (int) Math.ceil(factor)) + 1,
							2 * (2 + (int) Math.ceil(factor)) + 1);
				}));

		group.addControl(new JLabel("<html><i>higher weight is slower:<br>"
				+ "the search grid grows with it</i></html>"));

		return group;
	}

	private CollapsibleGroup crustTypeGroup() {
		CollapsibleGroup group = new CollapsibleGroup("land and ocean", false);

		group.addControl(slider("continental fraction", 0, PERCENT_SCALE,
				(int) Math.round(model.plateSettings().continentalFraction() * PERCENT_SCALE),
				value -> {
					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), p.nucleiSpacingBlocks(), p.nucleiMaxWeightFactor(),
							p.jitter(), value / (double) PERCENT_SCALE, p.seaLevel(),
							p.continentalBase(), p.oceanicBase(), p.baseVariation(),
							p.transformDominance(), p.warp(), p.continentWavelengthFactor()));

					return value + "%";
				}));

		group.addControl(slider("continent wavelength", PERCENT_SCALE, 120 * PERCENT_SCALE,
				(int) Math.round(model.plateSettings().continentWavelengthFactor() * PERCENT_SCALE),
				value -> {
					double factor = value / (double) PERCENT_SCALE;

					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), p.nucleiSpacingBlocks(), p.nucleiMaxWeightFactor(),
							p.jitter(), p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), p.transformDominance(),
							p.warp(), factor));

					return String.format("%.1f cells (%,.0f blocks)",
							factor, model.plateSettings().crustSpacingBlocks() * factor);
				}));

		group.addControl(slider("transform dominance", PERCENT_SCALE, 8 * PERCENT_SCALE,
				(int) Math.round(model.plateSettings().transformDominance() * PERCENT_SCALE),
				value -> {
					plates(p -> new PlateMapSettings(
							p.crustSpacingBlocks(), p.nucleiSpacingBlocks(), p.nucleiMaxWeightFactor(),
							p.jitter(), p.continentalFraction(), p.seaLevel(), p.continentalBase(),
							p.oceanicBase(), p.baseVariation(), value / (double) PERCENT_SCALE,
							p.warp(), p.continentWavelengthFactor()));

					return String.format("k = %.2f", value / (double) PERCENT_SCALE);
				}));

		return group;
	}

	private CollapsibleGroup regionGroup() {
		CollapsibleGroup group = new CollapsibleGroup("regions", false);

		group.addControl(slider("spacing", 500, 10_000,
				(int) model.regionSettings().spacingBlocks(),
				value -> {
					regions(r -> new RegionSettings(
							value, r.jitter(), r.warpStrengthBlocks(),
							r.warpWavelengthFactor(), r.warpOctaves(), r.blendFraction()));

					return String.format("%,d blocks", value);
				}));

		group.addControl(slider("warp strength", 0, 8_000,
				(int) model.regionSettings().warpStrengthBlocks(),
				value -> {
					regions(r -> new RegionSettings(
							r.spacingBlocks(), r.jitter(), value,
							r.warpWavelengthFactor(), r.warpOctaves(), r.blendFraction()));

					return String.format("%,d blocks", value);
				}));

		group.addControl(slider("edge blend", 0, 50,
				(int) Math.round(model.regionSettings().blendFraction() * PERCENT_SCALE),
				value -> {
					double fraction = value / (double) PERCENT_SCALE;

					regions(r -> new RegionSettings(
							r.spacingBlocks(), r.jitter(), r.warpStrengthBlocks(),
							r.warpWavelengthFactor(), r.warpOctaves(), fraction));

					return String.format("%.2fx spacing (%,.0f blocks)",
							fraction, model.regionSettings().spacingBlocks() * fraction);
				}));

		return group;
	}

	private void plates(final java.util.function.UnaryOperator<PlateMapSettings> edit) {
		model.setPlateSettings(edit.apply(model.plateSettings()));
	}

	private void regions(final java.util.function.UnaryOperator<RegionSettings> edit) {
		model.setRegionSettings(edit.apply(model.regionSettings()));
	}

	/**
	 * A slider with a live readout.
	 *
	 * <p>{@code formatter} applies the value and returns its label. It runs on every
	 * change so the readout tracks the drag, but the world is only rebuilt and
	 * re-rendered once the drag settles.
	 */
	private Component slider(
			final String name, final int min, final int max, final int initial,
			final IntFunction<String> formatter) {
		JLabel readout = new JLabel(formatter.apply(Math.max(min, Math.min(max, initial))));

		JSlider control = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
		control.addChangeListener(e -> {
			readout.setText(formatter.apply(control.getValue()));

			if (!control.getValueIsAdjusting()) {
				onChange.run();
			}
		});

		JPanel panel = new JPanel(new GridLayout(3, 1));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(new JLabel(name));
		panel.add(control);
		panel.add(readout);

		return panel;
	}

	private static final long serialVersionUID = 1L;
}
