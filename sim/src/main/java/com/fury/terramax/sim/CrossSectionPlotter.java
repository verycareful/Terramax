package com.fury.terramax.sim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import com.fury.terramax.core.terrain.HeightField;

/**
 * Plots elevation along a straight line as a side-on profile.
 *
 * <p>Top-down maps hide the thing that decides whether terrain is believable: the
 * shape of a slope. A mountain with vertical walls and a mountain with plausible
 * flanks look identical from above. This is where that difference shows up.
 *
 * <p>The vertical axis is drawn at the dimension's true range rather than
 * autoscaled to the data. Autoscaling would make a 40-block rise look like an
 * alp, which is precisely the self-deception the tool exists to prevent.
 */
public final class CrossSectionPlotter {
	private static final Color BACKGROUND = new Color(18, 20, 24);
	private static final Color GRID = new Color(44, 48, 56);
	private static final Color AXIS_TEXT = new Color(150, 158, 170);
	private static final Color SEA = new Color(48, 92, 150);
	private static final Color TERRAIN = new Color(228, 226, 214);
	private static final Color ROCK_FILL = new Color(74, 70, 62);

	private static final int MARGIN_LEFT = 72;
	private static final int MARGIN_RIGHT = 16;
	private static final int MARGIN_TOP = 28;
	private static final int MARGIN_BOTTOM = 42;

	/** Horizontal grid lines, and thus elevation labels. */
	private static final int ELEVATION_GRID_LINES = 8;
	private static final int DISTANCE_GRID_LINES = 8;

	private CrossSectionPlotter() {
	}

	/**
	 * @param field    elevation to plot
	 * @param startX   world x of the left end
	 * @param startZ   world z of the left end
	 * @param endX     world x of the right end
	 * @param endZ     world z of the right end
	 * @param minY     bottom of the vertical axis, in world Y
	 * @param maxY     top of the vertical axis, in world Y
	 * @param seaLevel world Y of sea level, drawn as a reference line
	 * @param width    image width in pixels
	 * @param height   image height in pixels
	 */
	public static BufferedImage plot(
			final HeightField field,
			final double startX, final double startZ,
			final double endX, final double endZ,
			final int minY, final int maxY,
			final int seaLevel,
			final int width, final int height) {

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(BACKGROUND);
		g.fillRect(0, 0, width, height);

		int plotLeft = MARGIN_LEFT;
		int plotRight = width - MARGIN_RIGHT;
		int plotTop = MARGIN_TOP;
		int plotBottom = height - MARGIN_BOTTOM;
		int plotWidth = plotRight - plotLeft;
		int plotHeight = plotBottom - plotTop;

		double lineLength = Math.hypot(endX - startX, endZ - startZ);

		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

		drawElevationGrid(g, plotLeft, plotRight, plotTop, plotHeight, minY, maxY);
		drawDistanceGrid(g, plotLeft, plotWidth, plotTop, plotBottom, lineLength);

		// Sample once per pixel column. Finer sampling would be invisible.
		int[] surface = new int[plotWidth];

		for (int i = 0; i < plotWidth; i++) {
			double t = plotWidth == 1 ? 0.0 : i / (double) (plotWidth - 1);
			double worldX = startX + (endX - startX) * t;
			double worldZ = startZ + (endZ - startZ) * t;

			surface[i] = toPixelY(field.heightAt(worldX, worldZ), minY, maxY, plotTop, plotHeight);
		}

		fillBelowSurface(g, surface, plotLeft, plotBottom);
		drawSeaLevel(g, plotLeft, plotRight, plotTop, plotHeight, minY, maxY, seaLevel);
		drawSurfaceLine(g, surface, plotLeft);

		g.setColor(GRID);
		g.drawRect(plotLeft, plotTop, plotWidth, plotHeight);

		g.setColor(AXIS_TEXT);
		g.drawString("elevation (world Y)", MARGIN_LEFT, MARGIN_TOP - 10);
		g.drawString(String.format("%,.0f blocks along section", lineLength),
				plotLeft, height - 12);

		g.dispose();

		return image;
	}

	private static void drawElevationGrid(
			final Graphics2D g, final int plotLeft, final int plotRight,
			final int plotTop, final int plotHeight, final int minY, final int maxY) {
		for (int i = 0; i <= ELEVATION_GRID_LINES; i++) {
			double fraction = i / (double) ELEVATION_GRID_LINES;
			int y = plotTop + (int) Math.round(plotHeight * (1.0 - fraction));
			int elevation = (int) Math.round(minY + (maxY - minY) * fraction);

			g.setColor(GRID);
			g.drawLine(plotLeft, y, plotRight, y);

			g.setColor(AXIS_TEXT);
			g.drawString(String.format("%6d", elevation), 8, y + 4);
		}
	}

	private static void drawDistanceGrid(
			final Graphics2D g, final int plotLeft, final int plotWidth,
			final int plotTop, final int plotBottom, final double lineLength) {
		for (int i = 0; i <= DISTANCE_GRID_LINES; i++) {
			double fraction = i / (double) DISTANCE_GRID_LINES;
			int x = plotLeft + (int) Math.round(plotWidth * fraction);

			g.setColor(GRID);
			g.drawLine(x, plotTop, x, plotBottom);

			g.setColor(AXIS_TEXT);
			String label = String.format("%,.0fk", lineLength * fraction / 1000.0);
			g.drawString(label, x - 12, plotBottom + 16);
		}
	}

	private static void fillBelowSurface(
			final Graphics2D g, final int[] surface, final int plotLeft, final int plotBottom) {
		g.setColor(ROCK_FILL);

		for (int i = 0; i < surface.length; i++) {
			int top = Math.min(surface[i], plotBottom);
			g.drawLine(plotLeft + i, top, plotLeft + i, plotBottom);
		}
	}

	private static void drawSeaLevel(
			final Graphics2D g, final int plotLeft, final int plotRight,
			final int plotTop, final int plotHeight,
			final int minY, final int maxY, final int seaLevel) {
		int y = toPixelY(seaLevel, minY, maxY, plotTop, plotHeight);

		g.setColor(SEA);
		g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
				10.0f, new float[] {6.0f, 6.0f}, 0.0f));
		g.drawLine(plotLeft, y, plotRight, y);
		g.setStroke(new BasicStroke(1.0f));

		g.drawString("sea " + seaLevel, plotRight - 56, y - 4);
	}

	private static void drawSurfaceLine(final Graphics2D g, final int[] surface, final int plotLeft) {
		g.setColor(TERRAIN);
		g.setStroke(new BasicStroke(1.6f));

		for (int i = 1; i < surface.length; i++) {
			g.drawLine(plotLeft + i - 1, surface[i - 1], plotLeft + i, surface[i]);
		}

		g.setStroke(new BasicStroke(1.0f));
	}

	private static int toPixelY(
			final double elevation, final int minY, final int maxY,
			final int plotTop, final int plotHeight) {
		double fraction = (elevation - minY) / (double) (maxY - minY);
		double clamped = Math.max(0.0, Math.min(1.0, fraction));

		return plotTop + (int) Math.round(plotHeight * (1.0 - clamped));
	}
}
