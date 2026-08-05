package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.fury.terramax.core.terrain.HeightField;

/**
 * Shows a terrain profile between two points picked on the map.
 *
 * <p>A top-down map cannot answer the questions that matter most when tuning:
 * whether a slope is walkable, whether a range has a usable pass, whether a plateau
 * edge is a cliff or a ramp. A section answers all three at a glance.
 *
 * <p>{@link CrossSectionPlotter} could already draw these but only took coordinates
 * from code, which meant recompiling to look somewhere else.
 */
public final class CrossSectionWindow {
	private static final int WIDTH = 1600;
	private static final int HEIGHT = 520;

	/** Leaves room for the scroll bars and title bar around the plot. */
	private static final int FRAME_MARGIN = 40;

	private CrossSectionWindow() {
	}

	/**
	 * Plots off the event thread, then opens a window.
	 *
	 * <p>A section across several plates is thousands of height evaluations, which is
	 * long enough to freeze the viewer if done inline.
	 */
	public static void show(
			final HeightField field,
			final double startX, final double startZ,
			final double endX, final double endZ,
			final int minY, final int maxY, final int seaLevel) {
		Thread worker = new Thread(() -> {
			BufferedImage image = CrossSectionPlotter.plot(
					field, startX, startZ, endX, endZ, minY, maxY, seaLevel, WIDTH, HEIGHT);

			double length = Math.hypot(endX - startX, endZ - startZ);

			SwingUtilities.invokeLater(() -> open(image, startX, startZ, endX, endZ, length));
		}, "terramax-cross-section");

		worker.setDaemon(true);
		worker.start();
	}

	private static void open(
			final BufferedImage image,
			final double startX, final double startZ,
			final double endX, final double endZ,
			final double length) {
		JFrame frame = new JFrame(String.format(
				"section  %,.0f, %,.0f  to  %,.0f, %,.0f   (%,.0f blocks)",
				startX, startZ, endX, endZ, length));

		// Disposed rather than exiting: closing a section must not take the viewer
		// with it, since the whole point is comparing several.
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.add(new JScrollPane(new JLabel(new ImageIcon(image))), BorderLayout.CENTER);
		frame.setPreferredSize(new Dimension(WIDTH + FRAME_MARGIN, HEIGHT + FRAME_MARGIN * 2));
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}
}
