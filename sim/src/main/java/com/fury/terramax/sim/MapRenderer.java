package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.image.BufferedImage;

import com.fury.terramax.core.climate.TemperatureField;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.region.RegionMap;
import com.fury.terramax.core.region.RegionType;
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

	/**
	 * Boundary distances beyond this multiple of crust spacing all read as
	 * "interior".
	 *
	 * <p>Four crust spacings, so 24,000 blocks at the default. It was 0.25 when this
	 * multiplied 100,000-block plate spacing; against 6,000-block crust cells that
	 * would be 1,500 blocks and every distance layer would be almost entirely dark.
	 */
	private static final double INTERIOR_FRACTION = 4.0;

	/** Boundaries within this many blocks are drawn as a hard line. */
	private static final double EDGE_LINE_BLOCKS = 1.5;

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

	/**
	 * Perceptual spectrum from world floor to ceiling: black through deep blue and
	 * purple into red and orange, ending white.
	 *
	 * <p>Linear over the full range with nothing overlaid, and deliberately no sea
	 * level marker. This is a data view rather than a geographic one. The hypsometric
	 * palette above shows where the coast is; this shows the small height differences
	 * the hypsometric flattens into a single green.
	 */
	private static final Color[] MAGMA_RAMP = {
		new Color(0, 0, 4),
		new Color(40, 11, 84),
		new Color(101, 21, 110),
		new Color(159, 42, 99),
		new Color(212, 72, 66),
		new Color(245, 125, 21),
		new Color(250, 193, 39),
		new Color(252, 253, 191)
	};

	/**
	 * Colours for {@link TerrainLayer#REGION_TYPE}, indexed by {@code ordinal()}.
	 *
	 * <p>Kept in the same order as {@code RegionType.values()}, and the static block
	 * below fails loudly if that stops being true. Without it, adding a region type
	 * would throw an array index exception from inside a render thread, which
	 * surfaces as a blank tile rather than as the mistake it is.
	 */
	private static final Color[] REGION_TYPE_COLOURS = {
		new Color(154, 190, 116),
		new Color(122, 158, 92),
		new Color(196, 174, 122),
		new Color(150, 128, 96),
		new Color(206, 196, 148),
		new Color(184, 124, 88),
		new Color(38, 70, 120)
	};

	static {
		if (REGION_TYPE_COLOURS.length != RegionType.values().length) {
			throw new IllegalStateException(
					"REGION_TYPE_COLOURS has " + REGION_TYPE_COLOURS.length
							+ " entries but RegionType has " + RegionType.values().length);
		}
	}

	private MapRenderer() {
	}

	/** Magma spectrum colour for a height, linear across the dimension's full range. */
	public static Color magmaColour(final double height, final int minY, final int maxY) {
		return rampColour(MAGMA_RAMP, (height - minY) / (double) (maxY - minY));
	}

	/** Grayscale for a height, linear across the dimension's full range. */
	public static Color rawColour(final double height, final int minY, final int maxY) {
		int level = clampByte(255.0 * (height - minY) / (double) (maxY - minY));

		return new Color(level, level, level);
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
		return TileRenderer.renderAll(view, (worldX, worldZ) ->
				elevationColour(field.heightAt(worldX, worldZ), minY, maxY, seaLevel).getRGB());
	}

	/** Colour a region type is drawn in, for the legend to match the map. */
	public static Color regionTypeColour(final RegionType type) {
		return REGION_TYPE_COLOURS[type.ordinal()];
	}

	public static Color elevationColour(
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

		/** Land against ocean, shaded by each crust cell's base elevation. */
		CRUST_TYPE,

		/** Boundaries coloured by what the two plates are doing to each other. */
		BOUNDARY_TYPE
	}

	/** Layers needing the height field and the region lattice, not just plates. */
	public enum TerrainLayer {
		/** Magma spectrum over the dimension's full vertical range. */
		ELEVATION_MAGMA,

		/**
		 * Plain grayscale, black at the world floor to white at the ceiling.
		 *
		 * <p>The standard heightmap form, and useful for a reason the colour ramps
		 * are not: with no hue to distract, banding, terracing and seams show up
		 * immediately. A discontinuity that reads as a slight shift of green in the
		 * hypsometric palette is an obvious step in grayscale.
		 */
		ELEVATION_RAW,

		/** Hypsometric palette with a crisp coastline at sea level. */
		ELEVATION_HYPSOMETRIC,

		/** One colour per terrain type, so the region map is directly readable. */
		REGION_TYPE,

		/** A stable colour per region, to check region size and shape. */
		REGION_ID,

		/** Temperature in degrees C, cold blue through white to hot red. */
		TEMPERATURE,

		/**
		 * Life zones, cut by treeline and snowline.
		 *
		 * <p>The layer that proves the lapse rate works. Neither line is authored:
		 * both are contours where the temperature field crosses a fixed threshold, so
		 * a tropical mountain and an arctic shoreline reach the same zone at wildly
		 * different altitudes without a single per-range setting.
		 */
		LIFE_ZONE
	}

	/** Cold to hot, in degrees C. Diverging about freezing rather than about zero. */
	private static final Color[] TEMPERATURE_RAMP = {
		new Color(40, 40, 120),
		new Color(70, 130, 200),
		new Color(180, 220, 240),
		new Color(250, 245, 200),
		new Color(240, 170, 80),
		new Color(200, 60, 40)
	};

	/** Coldest and hottest the temperature ramp spans, in degrees C. */
	private static final double TEMPERATURE_MIN = -45.0;
	private static final double TEMPERATURE_MAX = 40.0;

	private static final Color ZONE_OCEAN = new Color(38, 70, 120);
	private static final Color ZONE_FOREST = new Color(58, 104, 58);
	private static final Color ZONE_ALPINE = new Color(150, 158, 108);
	private static final Color ZONE_SNOW = new Color(244, 246, 250);

	private static int temperatureColour(
			final HeightField field, final TemperatureField climate,
			final double worldX, final double worldZ) {
		double celsius = climate.at(worldX, worldZ, field.heightAt(worldX, worldZ));

		return rampColour(TEMPERATURE_RAMP,
				(celsius - TEMPERATURE_MIN) / (TEMPERATURE_MAX - TEMPERATURE_MIN)).getRGB();
	}

	private static int lifeZoneColour(
			final HeightField field, final TemperatureField climate,
			final double worldX, final double worldZ, final int seaLevel) {
		double height = field.heightAt(worldX, worldZ);

		if (height <= seaLevel) {
			return ZONE_OCEAN.getRGB();
		}

		double celsius = climate.at(worldX, worldZ, height);

		if (celsius < TemperatureField.SNOWLINE_CELSIUS) {
			return ZONE_SNOW.getRGB();
		}

		return (celsius < TemperatureField.TREELINE_CELSIUS ? ZONE_ALPINE : ZONE_FOREST).getRGB();
	}

	public static BufferedImage render(final PlateMap plates, final MapView view, final Layer layer) {
		return renderProgressive(plates, view, layer, image -> {
		});
	}

	/**
	 * Renders a plate layer, notifying as each tile completes.
	 *
	 * <p>No plate-centre markers: sites live in warped space, so drawing them at
	 * unwarped screen positions would place them outside their own plates.
	 */
	public static BufferedImage renderProgressive(
			final PlateMap plates, final MapView view, final Layer layer,
			final TileRenderer.TileListener listener) {
		double spacing = plates.settings().crustSpacingBlocks();
		double interiorDistance = spacing * INTERIOR_FRACTION;
		double edgeLineBlocks = Math.max(EDGE_LINE_BLOCKS, view.blocksPerPixel());

		return TileRenderer.render(view, (worldX, worldZ) -> {
			PlateSample sample = plates.sample(worldX, worldZ);

			return switch (layer) {
				case PLATES_WITH_EDGES -> platesWithEdges(sample, interiorDistance, edgeLineBlocks);
				case BOUNDARY_DISTANCE -> boundaryShade(sample, interiorDistance);
				case CRUST_TYPE -> crustTypeColour(sample, plates, edgeLineBlocks);
				case BOUNDARY_TYPE -> boundaryTypeColour(sample, interiorDistance);
			};
		}, listener);
	}

	public static BufferedImage renderTerrain(
			final TerrainModel.Snapshot world, final MapView view, final TerrainLayer layer,
			final int minY, final int maxY, final int seaLevel) {
		return renderTerrainProgressive(world, view, layer, minY, maxY, seaLevel, image -> {
		});
	}

	public static BufferedImage renderTerrainProgressive(
			final TerrainModel.Snapshot world, final MapView view, final TerrainLayer layer,
			final int minY, final int maxY, final int seaLevel,
			final TileRenderer.TileListener listener) {
		HeightField field = world.terrain();
		PlateMap plates = world.plates();
		RegionMap regions = world.regions();
		TemperatureField climate = world.temperature();

		return TileRenderer.render(view, (worldX, worldZ) -> switch (layer) {
			case ELEVATION_MAGMA ->
					magmaColour(field.heightAt(worldX, worldZ), minY, maxY).getRGB();
			case ELEVATION_RAW ->
					rawColour(field.heightAt(worldX, worldZ), minY, maxY).getRGB();
			case ELEVATION_HYPSOMETRIC ->
					elevationColour(field.heightAt(worldX, worldZ), minY, maxY, seaLevel).getRGB();
			case REGION_TYPE -> regionTypeColour(plates, regions, worldX, worldZ);
			case REGION_ID -> regionIdColour(plates, regions, worldX, worldZ);
			case TEMPERATURE -> temperatureColour(field, climate, worldX, worldZ);
			case LIFE_ZONE -> lifeZoneColour(field, climate, worldX, worldZ, seaLevel);
		}, listener);
	}

	private static int regionTypeColour(
			final PlateMap plates, final RegionMap regions,
			final double worldX, final double worldZ) {
		var crust = plates.sample(worldX, worldZ).crust().crustType();
		var region = regions.sample(worldX, worldZ, crust).region();

		return REGION_TYPE_COLOURS[region.type().ordinal()].getRGB();
	}

	private static int regionIdColour(
			final PlateMap plates, final RegionMap regions,
			final double worldX, final double worldZ) {
		var crust = plates.sample(worldX, worldZ).crust().crustType();
		var region = regions.sample(worldX, worldZ, crust).region();

		long id = Hashing.hash(0L, region.cellX(), region.cellZ());
		float hue = ((id >>> 40) & 0xFFFF) / (float) 0x10000;

		return Color.getHSBColor(hue, PLATE_SATURATION, PLATE_BRIGHTNESS).getRGB();
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
	 * Land against ocean, each crust cell shaded by its own base elevation so the
	 * spread within a type is visible rather than flattened.
	 *
	 * <p>Crust type is a property of the cell, not the plate, so coastlines here are
	 * independent of the boundary lines. Land crossing a boundary, and boundaries
	 * running through open ocean, are the expected result and the whole point of the
	 * change.
	 */
	private static int crustTypeColour(
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
			case NONE -> Color.BLACK;
		};

		double proximity = 1.0 - clamp01(sample.boundaryDistance() / interiorDistance);

		return scale(base, proximity).getRGB();
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
