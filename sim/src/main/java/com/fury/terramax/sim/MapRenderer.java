package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.image.BufferedImage;

import com.fury.terramax.core.climate.Moisture;
import com.fury.terramax.core.climate.MoistureField;
import com.fury.terramax.core.climate.SurfaceClimate;
import com.fury.terramax.core.climate.TemperatureField;
import com.fury.terramax.core.climate.Wind;
import com.fury.terramax.core.climate.WindField;
import com.fury.terramax.core.fluvial.BasinIndex;
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

		/**
		 * A stable colour per drainage basin, hashed from its outlet.
		 *
		 * <p><b>The layer that exposes a seam instantly.</b> Basins are keyed by the
		 * outlet they drain to rather than by the tile that resolved them, precisely so
		 * that two tiles containing the same straddling basin agree. If that ever broke,
		 * this render would show a colour change running dead straight along a tile
		 * boundary, through terrain that contains no straight lines at all.
		 *
		 * <p>Correct output has basin edges that wander along ridges, since a basin
		 * boundary is a drainage divide.
		 */
		BASIN_ID,

		/**
		 * Channels drawn as lines, thickened and brightened by Strahler order.
		 *
		 * <p>The payoff render for the whole subsystem, and the one that answers
		 * questions statistics cannot: whether the network is dendritic, whether
		 * tributaries join facing downstream, whether trunks sit in valleys rather
		 * than on ridges, and whether anything crosses anything else.
		 */
		DRAINAGE,

		/**
		 * Accumulated discharge on a logarithmic ramp.
		 *
		 * <p>Logarithmic because discharge is a power law. On a linear ramp one trunk
		 * would be visible and every tributary in the world would be black.
		 */
		DISCHARGE,

		/**
		 * Hillslope position: dark on channels, bright on divides.
		 *
		 * <p>Makes the divide network visible although no divide is ever built. The
		 * bright ridgelines here should coincide with ridges in the elevation render;
		 * if they do not, the two-nearest search is finding the wrong channels.
		 */
		HILLSLOPE,

		/**
		 * Standing water, coloured by what kind.
		 *
		 * <p>Three colours rather than one, because the distinction is the whole point
		 * of the water balance. A lake that overflows, a terminal lake that does not,
		 * and a dry playa are three different outcomes of the same calculation, and a
		 * single blue would hide which one happened.
		 *
		 * <p>The check to run against this render is against precipitation: playas and
		 * terminal lakes belong in arid interiors. One in a wet region means the
		 * balance is inverted.
		 */
		LAKES,

		/**
		 * How far the carve has cut below the uplift budget, on its own ramp.
		 *
		 * <p>Exists because the greyscale layers cannot answer this. They stretch their
		 * ramp over the world's full 2,048-block vertical range, so a valley 60 blocks
		 * deep is three percent of the ramp and invisible, whether the carve is working
		 * or doing nothing at all. That is an instrument problem, and it hid the fact
		 * that channel beds were being read off the uplift surface and cutting nothing.
		 *
		 * <p>This renders the difference directly, scaled to the depths that actually
		 * occur, so gorges, dissected uplands and uncut plains are told apart at a
		 * glance.
		 */
		INCISION,

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
		LIFE_ZONE,

		/**
		 * Prevailing wind: hue is direction, brightness is speed.
		 *
		 * <p>The circulation bands read as horizontal stripes because the base flow
		 * depends only on latitude, and terrain deflection shows as distortion in
		 * them. Dark bands are the three calm belts.
		 */
		WIND,

		/**
		 * Rain falling here, dry sand through green to deep blue.
		 *
		 * <p>The layer that proves the model. Nothing in it is authored: the wet
		 * windward face, the dry lee, the parched continental interior and the
		 * equatorial belt are all one air parcel losing what it cannot hold.
		 */
		PRECIPITATION,

		/**
		 * Vapour as a fraction of what this air could hold.
		 *
		 * <p>Read beside {@link #PRECIPITATION} rather than instead of it. Where this
		 * is high and that is low is a coastal desert: saturated air with nothing to
		 * lift it. A single moisture scalar cannot show that place exists.
		 */
		HUMIDITY,

		/**
		 * How much warmer the arriving air is than its latitude and altitude imply.
		 *
		 * <p>Red on the lee of a range is a foehn, and it is not coded anywhere. It
		 * is what is left over after air cooled slowly on the way up, because it was
		 * raining, and warmed quickly on the way down, because it had nothing left.
		 */
		FOEHN_WARMING
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
			final HeightField field, final SurfaceClimate climate,
			final double worldX, final double worldZ) {
		double celsius = climate.at(worldX, worldZ, field.heightAt(worldX, worldZ));

		return rampColour(TEMPERATURE_RAMP,
				(celsius - TEMPERATURE_MIN) / (TEMPERATURE_MAX - TEMPERATURE_MIN)).getRGB();
	}

	/** Parched through temperate to soaking. Ends violet so the extremes separate. */
	private static final Color[] PRECIPITATION_RAMP = {
		new Color(196, 172, 128),
		new Color(206, 200, 140),
		new Color(150, 190, 110),
		new Color(70, 160, 110),
		new Color(40, 120, 160),
		new Color(40, 60, 150),
		new Color(90, 40, 130)
	};

	/**
	 * Rain rate at the top of the ramp, per 1,000 blocks of path.
	 *
	 * <p>The ramp is applied to the square root of the rate rather than the rate
	 * itself. Precipitation is roughly exponential in what the air is carrying, so a
	 * linear ramp puts every desert, steppe and dry forest in the first two percent
	 * of the scale and spends the rest resolving the difference between two kinds of
	 * rainforest. The boundary worth seeing is the dry one.
	 *
	 * <p>The value is read off the climate transect rather than chosen: it is roughly
	 * the wettest windward slope in the tropics, so the ramp spends its whole length
	 * on rates that occur.
	 */
	private static final double PRECIPITATION_MAX = 0.06;

	/** Bone dry through to saturated. */
	private static final Color[] HUMIDITY_RAMP = {
		new Color(140, 100, 60),
		new Color(196, 168, 110),
		new Color(210, 210, 180),
		new Color(130, 190, 190),
		new Color(50, 150, 190)
	};

	/** Cooler than ambient, through neutral, to foehn-warmed. */
	private static final Color[] FOEHN_RAMP = {
		new Color(60, 90, 180),
		new Color(150, 175, 220),
		new Color(240, 240, 240),
		new Color(230, 160, 110),
		new Color(190, 50, 40)
	};

	/** Departure from ambient, in degrees C, at each end of the foehn ramp. */
	private static final double FOEHN_RANGE_CELSIUS = 8.0;

	private static int precipitationColour(
			final MoistureField moisture, final double worldX, final double worldZ) {
		Moisture air = moisture.at(worldX, worldZ);
		double rate = Math.max(0.0, air.precipitation());

		return rampColour(PRECIPITATION_RAMP,
				Math.sqrt(rate / PRECIPITATION_MAX)).getRGB();
	}

	private static int humidityColour(
			final MoistureField moisture, final double worldX, final double worldZ) {
		return rampColour(HUMIDITY_RAMP, moisture.at(worldX, worldZ).humidity()).getRGB();
	}

	private static int foehnColour(
			final MoistureField moisture, final double worldX, final double worldZ) {
		double warming = moisture.at(worldX, worldZ).foehnWarming();

		return rampColour(FOEHN_RAMP,
				0.5 + 0.5 * warming / FOEHN_RANGE_CELSIUS).getRGB();
	}

	private static int windColour(
			final WindField wind, final TemperatureField climate,
			final double worldX, final double worldZ) {
		Wind flow = wind.at(worldX, worldZ, climate.latitude(worldZ));

		// Hue from bearing, so opposite winds are opposite colours and a deflection
		// of a few degrees is visible as a shift rather than needing arrows.
		float hue = (float) ((flow.bearing() + Math.PI) / (2.0 * Math.PI));
		float brightness = (float) Math.min(1.0, flow.speed());

		return Color.getHSBColor(hue, 0.75f, brightness).getRGB();
	}

	private static int lifeZoneColour(
			final HeightField field, final SurfaceClimate climate,
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
		// Creeks only where they would be visible. Same choice MoistureScale makes
		// one line below, for the same reason.
		HeightField field = world.terrainFor(view.blocksPerPixel());
		boolean creeks = view.blocksPerPixel() <= world.drainage().creekVisibleBelowBlocks();
		PlateMap plates = world.plates();
		RegionMap regions = world.regions();
		TemperatureField climate = world.temperature();

		// Solved no finer than the screen can show. A planetary view would otherwise
		// put every pixel on its own trajectory and take hours.
		MoistureField moisture = world.moisture().forResolution(view.blocksPerPixel());

		// Temperature and life zone read the coupled field, not the bare one, so the
		// snowline sits higher on a sheltered lee than on the windward face opposite.
		SurfaceClimate surface = world.moisture().surfaceFor(view.blocksPerPixel());

		return TileRenderer.render(view, (worldX, worldZ) -> switch (layer) {
			case ELEVATION_MAGMA ->
					magmaColour(field.heightAt(worldX, worldZ), minY, maxY).getRGB();
			case ELEVATION_RAW ->
					rawColour(field.heightAt(worldX, worldZ), minY, maxY).getRGB();
			case ELEVATION_HYPSOMETRIC ->
					elevationColour(field.heightAt(worldX, worldZ), minY, maxY, seaLevel).getRGB();
			case REGION_TYPE -> regionTypeColour(plates, regions, worldX, worldZ);
			case REGION_ID -> regionIdColour(plates, regions, worldX, worldZ);
			case BASIN_ID -> basinIdColour(world.basins(), field, worldX, worldZ, seaLevel);
			case DRAINAGE -> drainageColour(world, field, worldX, worldZ, minY, maxY, seaLevel, creeks);
			case DISCHARGE -> dischargeColour(world, field, worldX, worldZ, seaLevel, creeks);
			case HILLSLOPE -> hillslopeColour(world, field, worldX, worldZ, seaLevel, creeks);
			case LAKES -> lakeColour(world, field, worldX, worldZ, minY, maxY, seaLevel, creeks);
			case INCISION -> incisionColour(world, field, worldX, worldZ, seaLevel);
			case TEMPERATURE -> temperatureColour(field, surface, worldX, worldZ);
			case LIFE_ZONE -> lifeZoneColour(field, surface, worldX, worldZ, seaLevel);
			case WIND -> windColour(world.wind(), climate, worldX, worldZ);
			case PRECIPITATION -> precipitationColour(moisture, worldX, worldZ);
			case HUMIDITY -> humidityColour(moisture, worldX, worldZ);
			case FOEHN_WARMING -> foehnColour(moisture, worldX, worldZ);
		}, listener);
	}

	private static int regionTypeColour(
			final PlateMap plates, final RegionMap regions,
			final double worldX, final double worldZ) {
		var crust = plates.sample(worldX, worldZ).crust().crustType();
		var region = regions.sample(worldX, worldZ, crust).region();

		return REGION_TYPE_COLOURS[region.type().ordinal()].getRGB();
	}

	/**
	 * Basin colour, or flat blue at sea.
	 *
	 * <p>Ocean is excluded rather than coloured, because every ocean point drains to
	 * itself and colouring them would fill the render with per-pixel confetti that
	 * hides the thing being looked at.
	 */
	/** Half-width in blocks of a drawn channel, by Strahler order. */
	private static double channelHalfWidth(final int order, final double blocksPerPixel) {
		// At least a pixel wide, or a continental view shows nothing at all, and wider
		// with order so a trunk reads as a trunk.
		return Math.max(blocksPerPixel * 0.7, 60.0 * Math.pow(1.5, Math.max(0, order - 1)));
	}

	private static int drainageColour(
			final TerrainModel.Snapshot world, final HeightField field,
			final double worldX, final double worldZ,
			final int minY, final int maxY, final int seaLevel, final boolean creeks) {
		double height = field.heightAt(worldX, worldZ);

		if (height <= seaLevel) {
			return OCEAN.getRGB();
		}

		var drain = world.drainage().sample(worldX, worldZ, creeks);
		double halfWidth = channelHalfWidth(drain.order(), 1.0);

		if (drain.hasChannel() && drain.distance() <= halfWidth) {
			float depth = (float) Math.min(1.0, 0.35 + 0.15 * drain.order());

			return new Color(0.1f, 0.45f * depth, 0.95f * depth).getRGB();
		}

		// Ground behind the channels, dimmed so the network reads on top of it.
		Color ground = rawColour(height, minY, maxY);

		return new Color(
				ground.getRed() / 3, ground.getGreen() / 3, ground.getBlue() / 3).getRGB();
	}

	private static int dischargeColour(
			final TerrainModel.Snapshot world, final HeightField field,
			final double worldX, final double worldZ, final int seaLevel, final boolean creeks) {
		if (field.heightAt(worldX, worldZ) <= seaLevel) {
			return OCEAN.getRGB();
		}

		var drain = world.drainage().sample(worldX, worldZ, creeks);

		if (!drain.hasChannel()) {
			return Color.BLACK.getRGB();
		}

		// Logarithmic, because discharge is a power law and a linear ramp would show
		// one trunk against black everywhere else.
		double scaled = Math.log1p(drain.discharge() * 1_000.0) / Math.log1p(1_000.0);

		return rampColour(MAGMA_RAMP, scaled).getRGB();
	}

	private static int hillslopeColour(
			final TerrainModel.Snapshot world, final HeightField field,
			final double worldX, final double worldZ, final int seaLevel, final boolean creeks) {
		if (field.heightAt(worldX, worldZ) <= seaLevel) {
			return OCEAN.getRGB();
		}

		float t = (float) world.drainage().sample(worldX, worldZ, creeks).hillslope();

		return new Color(t, t, t).getRGB();
	}

	/** Cut depth at which the incision ramp saturates, in blocks. */
	private static final double INCISION_FULL_BLOCKS = 120.0;

	private static int incisionColour(
			final TerrainModel.Snapshot world, final HeightField field,
			final double worldX, final double worldZ, final int seaLevel) {
		double height = field.heightAt(worldX, worldZ);

		if (height <= seaLevel) {
			return OCEAN.getRGB();
		}

		double cut = world.uplift().heightAt(worldX, worldZ) - height;

		// Square root, because cut depth is as skewed as the discharge that drives it:
		// a few gorges hundreds of blocks deep against a landscape of shallow valleys.
		// A linear ramp would show the gorges and call everything else uncut.
		double scaled = Math.min(1.0, Math.sqrt(Math.max(0.0, cut) / INCISION_FULL_BLOCKS));

		return rampColour(MAGMA_RAMP, scaled).getRGB();
	}

	private static final Color LAKE_OVERFLOW = new Color(70, 150, 235);
	private static final Color LAKE_TERMINAL = new Color(180, 90, 200);
	private static final Color LAKE_PLAYA = new Color(228, 214, 170);

	private static int lakeColour(
			final TerrainModel.Snapshot world, final HeightField field,
			final double worldX, final double worldZ,
			final int minY, final int maxY, final int seaLevel, final boolean creeks) {
		double height = field.heightAt(worldX, worldZ);

		if (height <= seaLevel) {
			return OCEAN.getRGB();
		}

		var drainage = world.drainage();
		double surface = drainage.sample(worldX, worldZ, creeks).lakeSurface();

		if (surface > height) {
			// Shaded by depth so a deep rift lake reads differently from a shallow pan.
			double depth = Math.min(1.0, (surface - height) / 60.0);
			Color base = drainage.terminalLakeAt(worldX, worldZ)
					? LAKE_TERMINAL : LAKE_OVERFLOW;
			float shade = (float) (0.45 + 0.55 * depth);

			return new Color(
					(int) (base.getRed() * shade),
					(int) (base.getGreen() * shade),
					(int) (base.getBlue() * shade)).getRGB();
		}

		if (drainage.playaAt(worldX, worldZ)) {
			return LAKE_PLAYA.getRGB();
		}

		Color ground = rawColour(height, minY, maxY);

		return new Color(
				ground.getRed() / 3, ground.getGreen() / 3, ground.getBlue() / 3).getRGB();
	}

	private static int basinIdColour(
			final BasinIndex basins, final HeightField field,
			final double worldX, final double worldZ, final int seaLevel) {
		if (field.heightAt(worldX, worldZ) <= seaLevel) {
			return OCEAN.getRGB();
		}

		long outlet = basins.outletAt(worldX, worldZ);
		long id = Hashing.hash(0L, BasinIndex.unpackCellX(outlet), BasinIndex.unpackCellZ(outlet));
		float hue = ((id >>> 40) & 0xFFFF) / (float) 0x10000;

		return Color.getHSBColor(hue, PLATE_SATURATION, PLATE_BRIGHTNESS).getRGB();
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
