package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.fury.terramax.core.terrain.HeightField;

/**
 * The cross section, docked along the bottom rather than opening its own window.
 *
 * <p>Docked because a section is read <em>against</em> the map: you draw a line
 * across a range and want to see both at once. A detached window covers the map you
 * just drew on, and comparing two sections means juggling three windows.
 *
 * <p>A top-down map cannot answer whether a slope is walkable, whether a range has a
 * usable pass, or whether a plateau edge is a cliff or a ramp. This can.
 */
public final class SectionPanel extends JPanel {
	private static final int PANEL_HEIGHT = 210;

	private static final Color BACKGROUND = new Color(18, 20, 24);
	private static final Color TEXT = new Color(226, 230, 238);

	private transient BufferedImage plot;
	private transient String caption = "";

	private transient Thread pending;

	public SectionPanel() {
		setLayout(new BorderLayout());
		setBackground(BACKGROUND);
		setPreferredSize(new Dimension(0, PANEL_HEIGHT));
		setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
	}

	/**
	 * Plots a profile between two points, off the event thread.
	 *
	 * <p>A section across several plates is thousands of height evaluations, each now
	 * costing three lattice lookups. Doing that inline would freeze the viewer.
	 */
	public void plot(
			final HeightField field,
			final double startX, final double startZ,
			final double endX, final double endZ) {
		if (pending != null) {
			pending.interrupt();
		}

		caption = String.format("plotting  %,.0f, %,.0f  to  %,.0f, %,.0f ...",
				startX, startZ, endX, endZ);
		repaint();

		int width = Math.max(200, getWidth() - 16);
		int height = Math.max(80, PANEL_HEIGHT - 40);

		pending = new Thread(() -> {
			BufferedImage image = CrossSectionPlotter.plot(
					field, startX, startZ, endX, endZ,
					MapPanel.MIN_Y, MapPanel.MAX_Y, MapPanel.SEA_LEVEL, width, height);

			double length = Math.hypot(endX - startX, endZ - startZ);

			if (Thread.currentThread().isInterrupted()) {
				return;
			}

			SwingUtilities.invokeLater(() -> {
				plot = image;
				caption = String.format("%,.0f, %,.0f   to   %,.0f, %,.0f      %,.0f blocks",
						startX, startZ, endX, endZ, length);
				repaint();
			});
		}, "terramax-section");

		pending.setDaemon(true);
		pending.start();
	}

	public void clear() {
		plot = null;
		caption = "";
		repaint();
	}

	@Override
	protected void paintComponent(final Graphics graphics) {
		super.paintComponent(graphics);

		Graphics2D g = (Graphics2D) graphics;
		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		g.setColor(TEXT);

		if (plot == null) {
			g.drawString(caption.isEmpty()
					? "section mode: click two points on the map"
					: caption, 12, 20);
			return;
		}

		g.drawString(caption, 12, 16);
		g.drawImage(plot, 8, 22, null);
	}

	private static final long serialVersionUID = 1L;
}
