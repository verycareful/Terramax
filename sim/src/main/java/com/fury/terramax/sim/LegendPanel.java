package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import com.fury.terramax.core.region.RegionType;

/**
 * Colour key for whatever layer is showing.
 *
 * <p>Without one, the region map is seven arbitrary greens and browns and the magma
 * ramp is a gradient with no numbers on it. Both are unreadable to anyone who has not
 * just written the palette.
 */
public final class LegendPanel extends JPanel {
	private static final int HEIGHT = 190;
	private static final int SWATCH = 12;
	private static final int ROW_HEIGHT = 16;
	private static final int MARGIN = 4;

	private static final Color TEXT = new Color(226, 230, 238);

	/** Elevations marked on the continuous ramps, in blocks. */
	private static final int[] RAMP_TICKS = {-256, 0, 400, 900, 1792};

	private transient MapRenderer.TerrainLayer terrainLayer;
	private transient MapRenderer.Layer plateLayer;

	public LegendPanel() {
		setPreferredSize(new Dimension(0, HEIGHT));
		setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
	}

	public void showTerrainLayer(final MapRenderer.TerrainLayer layer) {
		this.terrainLayer = layer;
		this.plateLayer = null;
		repaint();
	}

	public void showPlateLayer(final MapRenderer.Layer layer) {
		this.plateLayer = layer;
		this.terrainLayer = null;
		repaint();
	}

	@Override
	protected void paintComponent(final Graphics graphics) {
		super.paintComponent(graphics);

		Graphics2D g = (Graphics2D) graphics;
		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

		int x = 12;
		int y = 20;

		g.setColor(TEXT);
		g.drawString("LEGEND", x, y);

		y += ROW_HEIGHT;

		if (terrainLayer == null) {
			drawPlateLegend(g, x, y);
			return;
		}

		switch (terrainLayer) {
			case ELEVATION_MAGMA -> drawRamp(g, x, y,
					height -> MapRenderer.magmaColour(height, MapPanel.MIN_Y, MapPanel.MAX_Y));

			case ELEVATION_RAW -> drawRamp(g, x, y,
					height -> MapRenderer.rawColour(height, MapPanel.MIN_Y, MapPanel.MAX_Y));

			case ELEVATION_HYPSOMETRIC -> drawRamp(g, x, y,
					height -> MapRenderer.elevationColour(
							height, MapPanel.MIN_Y, MapPanel.MAX_Y, MapPanel.SEA_LEVEL));

			case REGION_TYPE -> drawRegionTypes(g, x, y);

			case REGION_ID -> g.drawString("one hue per region", x, y);

			case TEMPERATURE -> {
				g.drawString("cold blue to hot red", x, y);
				g.drawString("-45 C to +40 C", x, y + ROW_HEIGHT);
			}

			case LIFE_ZONE -> {
				String[] zones = {"forest, above 6 C", "alpine, 6 C to -4 C",
					"permanent snow, below -4 C", "ocean"};
				Color[] colours = {new Color(58, 104, 58), new Color(150, 158, 108),
					new Color(244, 246, 250), new Color(38, 70, 120)};

				int row = y;

				for (int i = 0; i < zones.length; i++) {
					g.setColor(colours[i]);
					g.fillRect(x, row - SWATCH + MARGIN, SWATCH, SWATCH);
					g.setColor(TEXT);
					g.drawString(zones[i], x + SWATCH + 8, row);
					row += ROW_HEIGHT;
				}
			}
		}
	}

	/** Draws the ramp as a strip with elevations marked, so a colour reads as a height. */
	private void drawRamp(final Graphics2D g, final int x, final int y, final RampColour ramp) {
		int width = getWidth() - x - 12;
		int stripHeight = 14;

		for (int i = 0; i < width; i++) {
			double height = MapPanel.MIN_Y
					+ (MapPanel.MAX_Y - MapPanel.MIN_Y) * (i / (double) width);

			g.setColor(ramp.at(height));
			g.drawLine(x + i, y, x + i, y + stripHeight);
		}

		g.setColor(TEXT);

		for (int tick : RAMP_TICKS) {
			int px = x + (int) Math.round(
					width * (tick - MapPanel.MIN_Y) / (double) (MapPanel.MAX_Y - MapPanel.MIN_Y));

			g.drawLine(px, y + stripHeight, px, y + stripHeight + 4);
			g.drawString(String.valueOf(tick), Math.min(px - 8, x + width - 30),
					y + stripHeight + 16);
		}
	}

	private void drawRegionTypes(final Graphics2D g, final int x, final int startY) {
		int y = startY;

		for (RegionType type : RegionType.values()) {
			g.setColor(MapRenderer.regionTypeColour(type));
			g.fillRect(x, y - SWATCH + MARGIN, SWATCH, SWATCH);

			g.setColor(TEXT);
			g.drawString(type.name().toLowerCase().replace('_', ' '), x + SWATCH + 8, y);

			y += ROW_HEIGHT;
		}
	}

	private void drawPlateLegend(final Graphics2D g, final int x, final int startY) {
		if (plateLayer == null) {
			return;
		}

		g.setColor(TEXT);

		String[] rows = switch (plateLayer) {
			case PLATES_WITH_EDGES -> new String[] {"one hue per plate", "black at boundaries"};
			case BOUNDARY_DISTANCE -> new String[] {"white at a boundary", "black in the interior"};
			case CRUST_TYPE -> new String[] {"green: continental", "blue: oceanic",
				"shade: base elevation"};
			case BOUNDARY_TYPE -> new String[] {"red: convergent", "blue: divergent",
				"yellow: transform", "black: plate interior"};
		};

		int y = startY;

		for (String row : rows) {
			g.drawString(row, x, y);
			y += ROW_HEIGHT;
		}
	}

	/** Maps an elevation to the colour a ramp gives it. */
	@FunctionalInterface
	private interface RampColour {
		Color at(double height);
	}

	private static final long serialVersionUID = 1L;
}
