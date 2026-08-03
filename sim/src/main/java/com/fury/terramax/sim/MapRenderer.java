package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.image.BufferedImage;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.terrain.HeightField;
import com.fury.terramax.core.util.Hashing;

/**
 * Renders top-down views of the plate system.
 *
 * <p>Every layer samples the same {@link PlateMap} the mod will use, so what
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

	private static final Color CONVERGENT = new Color(200, 60, 40);
	private static final Color DIVERGENT = new Color(60, 110, 210);
	private static final Color TRANSFORM = new Color(215, 180, 50);

	private static final Color LAND = new Color(96, 140, 74);
	private static final Color OCEAN = new Color(38, 70, 120);

	/**
	 * Hypsometric palette, from abyssal to summit. Stops are chosen so sea level
	 * falls exactly on the boundary between the last water colour and the first
	 * land colour, making the coastline crisp rather than a muddy gradient.
	 */
	private static final Color[] DEPTH_RAMP = {
		new Color(8, 20, 48),
		new Color(20, 52, 104),
		new Color(46, 96, 158),
		new Color(96, 152, 198)
	};

	/** Below 1.0 expands the low end of the land ramp, where nearly all land sits. */
	private static final double LAND_RAMP_GAMMA = 0.40;

	private static final Color[] LAND_RAMP = {
		new Color(206, 196, 148),
		new Color(112, 148, 84),
		new Color(74, 112, 62),
		new Color(126, 112, 72),
		new Color(138, 122, 108),
		new Color(186, 182, 178),
		new Color(248, 248, 250)
	};

	private MapRenderer() {
	}

	/**
	 * Renders elevation with a hypsometric palette.
	 *
	 * <p>Separate from {@link #render} because it needs a {@link HeightField} rather
	 * than a {@link PlateMap}: it draws the terrain, not the tectonics behind it.
	 */
	public static BufferedImage renderElevation(
			final HeightField field, final MapView view,
			final int minY, final int maxY, final int seaLevel) {
		BufferedImage image = new BufferedImage(view.pixels(), view.pixels(), BufferedImage.TYPE_INT_RGB);

		for (int py = 0; py < view.pixels(); py++) {
			double worldZ = view.worldZ(py);

			for (int px = 0; px < view.pixels(); px++) {
				double height = field.heightAt(view.worldX(px), worldZ);

				image.setRGB(px, py, elevationColour(height, minY, maxY, seaLevel).getRGB());
			}
		}

		return image;
	}

	private static Color elevationColour(
			final double height, final int minY, final int maxY, final int seaLevel) {
		if (height <= seaLevel) {
			double depth = clamp01((height - minY) / (double) (seaLevel - minY));

			return rampColour(DEPTH_RAMP, depth);
		}

		double altitude = clamp01((height - seaLevel) / (double) (maxY - seaLevel));

		// Curve the ramp toward low ground. Under 2% of land sits above y=1000, so a
		// linear ramp spends almost the whole palette on terrain that barely exists
		// and renders every ordinary hillside as the same flat colour.
		return rampColour(LAND_RAMP, Math.pow(altitude, LAND_RAMP_GAMMA));
	}

	/** Linear interpolation along a colour ramp, {@code t} in [0, 1]. */
	private static Color rampColour(final Color[] ramp, final double t) {
		double scaled = clamp01(t) * (ramp.length - 1);
		int index = (int) Math.floor(scaled);

		if (index >= ramp.length - 1) {
			return ramp[ramp.length - 1];
		}

		double blend = scaled - index;
		Color low = ramp[index];
		Color high = ramp[index + 1];

		return new Color(
				clampByte(low.getRed() + (high.getRed() - low.getRed()) * blend),
				clampByte(low.getGreen() + (high.getGreen() - low.getGreen()) * blend),
				clampByte(low.getBlue() + (high.getBlue() - low.getBlue()) * blend));
	}

	public enum Layer {
		/** Flat colour per plate, darkened toward boundaries, edges picked out. */
		PLATES_WITH_EDGES,

		/** Bright at boundaries, dark in interiors. This is where mountains will go. */
		BOUNDARY_DISTANCE,

		/** Land against ocean, shaded by each plate's base elevation. */
		PLATE_TYPE,

		/** Boundaries coloured by what the two plates are doing to each other. */
		BOUNDARY_TYPE
	}

	public static BufferedImage render(final PlateMap plates, final MapView view, final Layer layer) {
		BufferedImage image = new BufferedImage(view.pixels(), view.pixels(), BufferedImage.TYPE_INT_RGB);

		double spacing = plates.settings().crustSpacingBlocks();
		double interiorDistance = spacing * INTERIOR_FRACTION;
		double edgeLineBlocks = Math.max(EDGE_LINE_BLOCKS, view.blocksPerPixel());

		for (int py = 0; py < view.pixels(); py++) {
			double worldZ = view.worldZ(py);

			for (int px = 0; px < view.pixels(); px++) {
				double worldX = view.worldX(px);
				PlateSample sample = plates.sample(worldX, worldZ);

				image.setRGB(px, py, switch (layer) {
					case PLATES_WITH_EDGES -> platesWithEdges(sample, interiorDistance, edgeLineBlocks);
					case BOUNDARY_DISTANCE -> boundaryShade(sample, interiorDistance);
					case PLATE_TYPE -> plateTypeColour(sample, plates, edgeLineBlocks);
					case BOUNDARY_TYPE -> boundaryTypeColour(sample, interiorDistance);
				});
			}
		}

		// No plate-centre markers. Sites live in warped space, so drawing them at
		// unwarped screen positions would place them outside their own plates.
		return image;
	}

	/** A stable colour per plate, derived from its cell identity. */
	private static Color plateColour(final PlateSample sample) {
		long id = Hashing.hash(0L, sample.plate().cellX(), sample.plate().cellZ());
		float hue = ((id >>> 40) & 0xFFFF) / (float) 0x10000;

		return Color.getHSBColor(hue, PLATE_SATURATION, PLATE_BRIGHTNESS);
	}

	/** White at a boundary, fading to black by {@code interiorDistance} inland. */
	private static int boundaryShade(final PlateSample sample, final double interiorDistance) {
		double proximity = 1.0 - clamp01(sample.boundaryDistance() / interiorDistance);
		int level = (int) Math.round(proximity * 255.0);

		return (level << 16) | (level << 8) | level;
	}

	private static int platesWithEdges(
			final PlateSample sample, final double interiorDistance, final double edgeLineBlocks) {
		if (sample.boundaryDistance() <= edgeLineBlocks) {
			return Color.BLACK.getRGB();
		}

		Color base = plateColour(sample);
		double interior = clamp01(sample.boundaryDistance() / interiorDistance);

		return scale(base, 0.55 + 0.45 * interior).getRGB();
	}

	/**
	 * Land against ocean, each plate shaded by its own base elevation so the spread
	 * within a type is visible rather than flattened.
	 */
	private static int plateTypeColour(
			final PlateSample sample, final PlateMap plates, final double edgeLineBlocks) {
		if (sample.boundaryDistance() <= edgeLineBlocks) {
			return Color.BLACK.getRGB();
		}

		Color base = sample.crust().isContinental() ? LAND : OCEAN;

		int typeBase = sample.crust().isContinental()
				? plates.settings().continentalBase()
				: plates.settings().oceanicBase();

		double variation = plates.settings().baseVariation();
		double offset = variation == 0.0
				? 0.5
				: clamp01((sample.crust().baseElevation() - typeBase + variation) / (2.0 * variation));

		return scale(base, 0.70 + 0.55 * offset).getRGB();
	}

	/**
	 * Boundaries coloured by classification, fading out inland so the network of
	 * convergent, divergent and transform margins is legible at a glance.
	 */
	private static int boundaryTypeColour(final PlateSample sample, final double interiorDistance) {
		Color base = switch (sample.boundaryType()) {
			case CONVERGENT -> CONVERGENT;
			case DIVERGENT -> DIVERGENT;
			case TRANSFORM -> TRANSFORM;
		};

		double proximity = 1.0 - clamp01(sample.boundaryDistance() / interiorDistance);

		return scale(base, proximity).getRGB();
	}

	/** Marks plate centres, so jitter and spacing are directly checkable. */
	private static void drawSiteMarkers(
			final BufferedImage image, final PlateMap plates, final MapView view) {
		double half = view.spanBlocks() * 0.5;
		var sites = plates.voronoi().sites();

		long minCellX = sites.cellX(view.centreX() - half) - 1;
		long maxCellX = sites.cellX(view.centreX() + half) + 1;
		long minCellZ = sites.cellZ(view.centreZ() - half) - 1;
		long maxCellZ = sites.cellZ(view.centreZ() + half) + 1;

		for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
			for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
				int px = (int) Math.round(
						(sites.pointX(cellX, cellZ) - (view.centreX() - half)) / view.blocksPerPixel());
				int py = (int) Math.round(
						(sites.pointZ(cellX, cellZ) - (view.centreZ() - half)) / view.blocksPerPixel());

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

	private static Color scale(final Color base, final double factor) {
		return new Color(
				clampByte(base.getRed() * factor),
				clampByte(base.getGreen() * factor),
				clampByte(base.getBlue() * factor));
	}

	private static int clampByte(final double value) {
		return Math.max(0, Math.min(255, (int) Math.round(value)));
	}

	private static double clamp01(final double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
