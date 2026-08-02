package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.image.BufferedImage;

import com.fury.terramax.core.util.Hashing;
import com.fury.terramax.core.util.VoronoiSample;
import com.fury.terramax.core.util.VoronoiSolver;

/**
 * Renders top-down views of the plate system.
 *
 * <p>Every layer samples the same {@link VoronoiSolver} the mod will use, so what
 * appears here is what generates in-game.
 */
public final class MapRenderer {
	/** Plate fill colours: strong enough hues to separate neighbours, muted enough to overlay. */
	private static final float PLATE_SATURATION = 0.45f;
	private static final float PLATE_BRIGHTNESS = 0.80f;

	/** Boundary distances beyond this fraction of plate spacing all read as "interior". */
	private static final double INTERIOR_FRACTION = 0.25;

	/** Boundaries within this many blocks are drawn as a hard line. */
	private static final double EDGE_LINE_BLOCKS = 1.5;

	private static final int SITE_MARKER_RADIUS_PIXELS = 3;

	private MapRenderer() {
	}

	public enum Layer {
		/** Flat colour per plate. Shows plate shape and size at a glance. */
		PLATE_ID,

		/** Bright at boundaries, dark in interiors. This is where mountains will go. */
		BOUNDARY_DISTANCE,

		/** Plate colour, darkened toward boundaries, with edges picked out. */
		PLATES_WITH_EDGES
	}

	public static BufferedImage render(final VoronoiSolver solver, final MapView view, final Layer layer) {
		BufferedImage image = new BufferedImage(view.pixels(), view.pixels(), BufferedImage.TYPE_INT_RGB);

		double spacing = solver.sites().spacing();
		double interiorDistance = spacing * INTERIOR_FRACTION;
		double edgeLineBlocks = Math.max(EDGE_LINE_BLOCKS, view.blocksPerPixel());

		for (int py = 0; py < view.pixels(); py++) {
			double worldZ = view.worldZ(py);

			for (int px = 0; px < view.pixels(); px++) {
				double worldX = view.worldX(px);
				VoronoiSample sample = solver.sample(worldX, worldZ);

				image.setRGB(px, py, switch (layer) {
					case PLATE_ID -> plateColour(sample).getRGB();
					case BOUNDARY_DISTANCE -> boundaryShade(sample, interiorDistance);
					case PLATES_WITH_EDGES -> platesWithEdges(sample, interiorDistance, edgeLineBlocks);
				});
			}
		}

		if (layer == Layer.PLATES_WITH_EDGES) {
			drawSiteMarkers(image, solver, view);
		}

		return image;
	}

	/** A stable colour per plate, derived from its cell identity. */
	private static Color plateColour(final VoronoiSample sample) {
		long id = Hashing.hash(0L, sample.cellX(), sample.cellZ());
		float hue = ((id >>> 40) & 0xFFFF) / (float) 0x10000;

		return Color.getHSBColor(hue, PLATE_SATURATION, PLATE_BRIGHTNESS);
	}

	/** White at a boundary, fading to black by {@code interiorDistance} inland. */
	private static int boundaryShade(final VoronoiSample sample, final double interiorDistance) {
		double proximity = 1.0 - clamp01(sample.boundaryDistance() / interiorDistance);
		int level = (int) Math.round(proximity * 255.0);

		return (level << 16) | (level << 8) | level;
	}

	private static int platesWithEdges(
			final VoronoiSample sample, final double interiorDistance, final double edgeLineBlocks) {
		if (sample.boundaryDistance() <= edgeLineBlocks) {
			return Color.BLACK.getRGB();
		}

		Color base = plateColour(sample);

		// Darken toward the boundary so relief is legible before any terrain exists.
		double interior = clamp01(sample.boundaryDistance() / interiorDistance);
		double shade = 0.55 + 0.45 * interior;

		return new Color(
				(int) (base.getRed() * shade),
				(int) (base.getGreen() * shade),
				(int) (base.getBlue() * shade)).getRGB();
	}

	/** Marks plate centres, so jitter and spacing are directly checkable. */
	private static void drawSiteMarkers(
			final BufferedImage image, final VoronoiSolver solver, final MapView view) {
		double half = view.spanBlocks() * 0.5;
		long minCellX = solver.sites().cellX(view.centreX() - half) - 1;
		long maxCellX = solver.sites().cellX(view.centreX() + half) + 1;
		long minCellZ = solver.sites().cellZ(view.centreZ() - half) - 1;
		long maxCellZ = solver.sites().cellZ(view.centreZ() + half) + 1;

		for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
			for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
				double siteX = solver.sites().pointX(cellX, cellZ);
				double siteZ = solver.sites().pointZ(cellX, cellZ);

				int px = (int) Math.round((siteX - (view.centreX() - half)) / view.blocksPerPixel());
				int py = (int) Math.round((siteZ - (view.centreZ() - half)) / view.blocksPerPixel());

				fillMarker(image, px, py);
			}
		}
	}

	private static void fillMarker(final BufferedImage image, final int centreX, final int centreY) {
		for (int dy = -SITE_MARKER_RADIUS_PIXELS; dy <= SITE_MARKER_RADIUS_PIXELS; dy++) {
			for (int dx = -SITE_MARKER_RADIUS_PIXELS; dx <= SITE_MARKER_RADIUS_PIXELS; dx++) {
				if (dx * dx + dy * dy > SITE_MARKER_RADIUS_PIXELS * SITE_MARKER_RADIUS_PIXELS) {
					continue;
				}

				int x = centreX + dx;
				int y = centreY + dy;

				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
					image.setRGB(x, y, Color.WHITE.getRGB());
				}
			}
		}
	}

	private static double clamp01(final double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
