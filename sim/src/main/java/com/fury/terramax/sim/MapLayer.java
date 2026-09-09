package com.fury.terramax.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Every layer the map can draw, in one list, grouped by what it is about.
 *
 * <p>There are two renderer enums underneath: one for layers needing only the plate
 * lattice, one for layers needing the height field and everything downstream of it.
 * That split is real and belongs in the renderer. It has no business reaching the
 * user, who is choosing a picture rather than choosing a subsystem.
 *
 * <p>It used to reach them anyway. Both enums were poured into one combo box and the
 * selection was taken apart again with {@code instanceof}, which left twenty-one
 * entries in a flat list in enum declaration order, so {@code BASIN_ID} sat between
 * {@code REGION_ID} and {@code DRAINAGE} and the four plate layers were stranded at
 * the bottom under names nothing else used. The comment on that code conceded the
 * design was a workaround for the two families not fitting in one selector.
 *
 * <p>Naming them here rather than reusing the renderer's constant names is deliberate:
 * {@code ELEVATION_HYPSOMETRIC} describes the palette, and what a person is looking
 * for is "elevation".
 */
public enum MapLayer {
	PLATES(Category.PLATES, "plates", MapRenderer.Layer.PLATES_WITH_EDGES),
	CRUST(Category.PLATES, "land and ocean", MapRenderer.Layer.CRUST_TYPE),
	BOUNDARY_KIND(Category.PLATES, "boundary type", MapRenderer.Layer.BOUNDARY_TYPE),
	BOUNDARY_DISTANCE(Category.PLATES, "distance to boundary", MapRenderer.Layer.BOUNDARY_DISTANCE),

	ELEVATION(Category.TERRAIN, "elevation", MapRenderer.TerrainLayer.ELEVATION_HYPSOMETRIC),
	ELEVATION_SPECTRUM(Category.TERRAIN, "elevation, full range", MapRenderer.TerrainLayer.ELEVATION_MAGMA),
	ELEVATION_GREY(Category.TERRAIN, "elevation, greyscale", MapRenderer.TerrainLayer.ELEVATION_RAW),
	REGION_TYPE(Category.TERRAIN, "region type", MapRenderer.TerrainLayer.REGION_TYPE),
	REGION_ID(Category.TERRAIN, "region identity", MapRenderer.TerrainLayer.REGION_ID),

	BASINS(Category.WATER, "drainage basins", MapRenderer.TerrainLayer.BASIN_ID),
	RIVERS(Category.WATER, "rivers", MapRenderer.TerrainLayer.DRAINAGE),
	DISCHARGE(Category.WATER, "discharge", MapRenderer.TerrainLayer.DISCHARGE),
	LAKES(Category.WATER, "lakes and playas", MapRenderer.TerrainLayer.LAKES),
	INCISION(Category.WATER, "incision", MapRenderer.TerrainLayer.INCISION),
	HILLSLOPE(Category.WATER, "hillslope", MapRenderer.TerrainLayer.HILLSLOPE),

	TEMPERATURE(Category.CLIMATE, "temperature", MapRenderer.TerrainLayer.TEMPERATURE),
	LIFE_ZONE(Category.CLIMATE, "life zone", MapRenderer.TerrainLayer.LIFE_ZONE),
	WIND(Category.CLIMATE, "wind", MapRenderer.TerrainLayer.WIND),
	PRECIPITATION(Category.CLIMATE, "precipitation", MapRenderer.TerrainLayer.PRECIPITATION),
	HUMIDITY(Category.CLIMATE, "humidity", MapRenderer.TerrainLayer.HUMIDITY),
	FOEHN(Category.CLIMATE, "foehn warming", MapRenderer.TerrainLayer.FOEHN_WARMING);

	/** What a layer is about, which is how somebody looks for one. */
	public enum Category {
		PLATES("plates"),
		TERRAIN("terrain"),
		WATER("water"),
		CLIMATE("climate");

		private final String label;

		Category(final String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private final Category category;
	private final String label;
	private final MapRenderer.Layer plate;
	private final MapRenderer.TerrainLayer terrain;

	MapLayer(final Category category, final String label, final MapRenderer.Layer plate) {
		this(category, label, plate, null);
	}

	MapLayer(final Category category, final String label, final MapRenderer.TerrainLayer terrain) {
		this(category, label, null, terrain);
	}

	MapLayer(
			final Category category, final String label,
			final MapRenderer.Layer plate, final MapRenderer.TerrainLayer terrain) {
		this.category = category;
		this.label = label;
		this.plate = plate;
		this.terrain = terrain;
	}

	public Category category() {
		return category;
	}

	public String label() {
		return label;
	}

	/** The plate layer to render, or null where this is a terrain layer. */
	public MapRenderer.Layer plate() {
		return plate;
	}

	/** The terrain layer to render, or null where this is a plate layer. */
	public MapRenderer.TerrainLayer terrain() {
		return terrain;
	}

	public boolean isTerrain() {
		return terrain != null;
	}

	/** Layers in one category, in declaration order. */
	public static List<MapLayer> of(final Category category) {
		List<MapLayer> found = new ArrayList<>();

		for (MapLayer layer : values()) {
			if (layer.category == category) {
				found.add(layer);
			}
		}

		return found;
	}

	@Override
	public String toString() {
		return label;
	}
}
