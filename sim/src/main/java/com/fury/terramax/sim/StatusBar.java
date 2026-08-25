package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The bottom strip: where the cursor is and what is under it.
 *
 * <p>Answers the question the old viewer could not: "what am I looking at". Position,
 * height, crust, region type and the current scale, live under the pointer.
 */
public final class StatusBar extends JPanel {
	private static final Color BACKGROUND = new Color(34, 38, 46);
	private static final Color TEXT = new Color(196, 204, 218);

	private final JLabel cursor = new JLabel();
	private final JLabel render = new JLabel();

	public StatusBar() {
		setLayout(new BorderLayout());
		setBackground(BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 11);

		cursor.setFont(mono);
		cursor.setForeground(TEXT);

		render.setFont(mono);
		render.setForeground(TEXT);
		render.setHorizontalAlignment(JLabel.RIGHT);

		add(cursor, BorderLayout.WEST);
		add(render, BorderLayout.EAST);

		showHint(ViewerMode.PAN);
	}

	/** Shown when the cursor is off the map, so the strip is never blank. */
	public void showHint(final ViewerMode mode) {
		cursor.setText(mode.label() + " mode:  " + mode.hint());
	}

	/**
	 * Reports what sits under the cursor.
	 *
	 * <p>Evaluates the terrain once per mouse move. That is one height lookup at
	 * roughly mouse-event rate, which is negligible beside a render, and it is the
	 * whole reason the bar is useful rather than decorative.
	 */
	public void showPoint(final TerrainModel.Snapshot world, final double worldX, final double worldZ) {
		var plate = world.plates().sample(worldX, worldZ);
		var region = world.regions().sample(worldX, worldZ, plate.crust().crustType()).region();
		double height = world.terrain().heightAt(worldX, worldZ);

		// The canonical lattice, not the render's coarsened one, so the readout is
		// the number the game would produce rather than the number the picture shows.
		var surface = world.moisture().surfaceClimate();
		var air = surface.moisture().at(worldX, worldZ);

		double celsius = surface.at(worldX, worldZ, height);
		double anomaly = surface.airAnomaly(worldX, worldZ);

		cursor.setText(String.format(
				"x %,.0f   z %,.0f   y %,.0f   %.1f C%s   lat %.2f   rain %.2f   rh %.0f%%"
						+ "   %s   %s   plate %d,%d",
				worldX, worldZ, height, celsius,
				Math.abs(anomaly) < 0.5 ? "" : String.format(" (%+.1f foehn)", anomaly),
				world.temperature().latitude(worldZ),
				air.precipitation(), air.humidity() * 100,
				plate.crust().crustType().name().toLowerCase(),
				region.type().name().toLowerCase().replace('_', ' '),
				plate.plate().cellX(), plate.plate().cellZ()));
	}

	public void showRender(final MapView view, final long elapsedMs, final boolean draft) {
		render.setText(String.format("%,.2f blocks/px   %d ms%s",
				view.blocksPerPixel(), elapsedMs, draft ? "  (draft)" : ""));
	}

	private static final long serialVersionUID = 1L;
}
