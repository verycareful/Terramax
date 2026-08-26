package com.fury.terramax.sim;

import com.fury.terramax.core.climate.ClimateSettings;
import com.fury.terramax.core.climate.MoistureSettings;
import com.fury.terramax.core.climate.TemperatureField;
import com.fury.terramax.core.climate.WindField;
import com.fury.terramax.core.fluvial.BasinIndex;
import com.fury.terramax.core.fluvial.DrainageSettings;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateMapSettings;
import com.fury.terramax.core.region.RegionClimate;
import com.fury.terramax.core.region.RegionMap;
import com.fury.terramax.core.region.RegionSettings;
import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.core.terrain.TerrainHeight;
import com.fury.terramax.core.terrain.TerrainSettings;
import com.fury.terramax.core.terrain.UpliftHeight;

/**
 * Owns the simulator's settings and the world built from them.
 *
 * <p><b>Why this exists.</b> The viewer previously took three independent suppliers
 * and called all three per render, and the terrain supplier internally built its own
 * plate map. So every frame constructed two {@link PlateMap} instances and ran
 * {@code calibrateContinentThreshold} twice, which is 4,096 noise evaluations each
 * time, including on every step of a drag.
 *
 * <p>The fix is one owner. Settings change, the world is rebuilt once, and readers
 * take an immutable {@link Snapshot} that cannot change under them mid-render.
 *
 * <p>Rebuilding rather than mutating is deliberate and unchanged from before:
 * {@code PlateMap} calibrates its land threshold at construction, so a mutable
 * settings object would leave that calibration stale and silently wrong.
 */
public final class TerrainModel {
	/**
	 * Rain at or below which the ground counts as fully arid.
	 *
	 * <p>Both thresholds are read off the climate transect rather than chosen. The
	 * subtropical dry belt sits at or near zero and a wet windward slope reaches past
	 * 0.02, so this pair spans the range that actually occurs and puts the mixed band
	 * where most of the world is not.
	 */
	private static final double DRY_RAIN_RATE = 0.002;

	/** Rain at or above which the ground counts as fully humid. */
	private static final double WET_RAIN_RATE = 0.014;

	private long seed;
	private PlateMapSettings plateSettings;
	private RegionSettings regionSettings;
	private TerrainSettings terrainSettings;
	private ClimateSettings climateSettings;
	private MoistureSettings moistureSettings;
	private DrainageSettings drainageSettings;

	private Snapshot snapshot;

	public TerrainModel(final long seed) {
		this.seed = seed;
		this.plateSettings = PlateMapSettings.defaults();
		this.regionSettings = RegionSettings.defaults();
		this.terrainSettings = TerrainSettings.defaults();
		this.climateSettings = ClimateSettings.defaults();
		this.moistureSettings = MoistureSettings.defaults();
		this.drainageSettings = DrainageSettings.defaults();

		rebuild();
	}

	/**
	 * One consistent view of the world.
	 *
	 * <p>A render thread takes one of these and holds it. Without that, a settings
	 * change mid-render would leave the plate map and the terrain disagreeing about
	 * what world they are describing.
	 */
	public record Snapshot(
			PlateMap plates, RegionMap regions, TerrainHeight terrain,
			TemperatureField temperature, WindField wind, MoistureScale moisture,
			UpliftHeight uplift, BasinIndex basins) {
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	public long seed() {
		return seed;
	}

	public PlateMapSettings plateSettings() {
		return plateSettings;
	}

	public RegionSettings regionSettings() {
		return regionSettings;
	}

	public TerrainSettings terrainSettings() {
		return terrainSettings;
	}

	public void setSeed(final long newSeed) {
		this.seed = newSeed;
		rebuild();
	}

	public void setPlateSettings(final PlateMapSettings settings) {
		this.plateSettings = settings;
		rebuild();
	}

	public void setRegionSettings(final RegionSettings settings) {
		this.regionSettings = settings;
		rebuild();
	}

	public void setTerrainSettings(final TerrainSettings settings) {
		this.terrainSettings = settings;
		rebuild();
	}

	public ClimateSettings climateSettings() {
		return climateSettings;
	}

	public void setClimateSettings(final ClimateSettings settings) {
		this.climateSettings = settings;
		rebuild();
	}

	public MoistureSettings moistureSettings() {
		return moistureSettings;
	}

	public void setMoistureSettings(final MoistureSettings settings) {
		this.moistureSettings = settings;
		rebuild();
	}

	/**
	 * Builds the world in dependency order, which is also causal order.
	 *
	 * <p>Tectonics, then the climate they drive, then the regions that climate
	 * decides, then the finished surface. Every arrow points one way. Wind and
	 * moisture read {@link TectonicHeight} rather than the finished surface, which is
	 * what keeps it that way once drainage lands: terrain will depend on rivers,
	 * rivers on moisture, moisture on wind, and wind on terrain.
	 */
	private void rebuild() {
		PlateMap plates = new PlateMap(seed, plateSettings);
		TectonicHeight tectonic = new TectonicHeight(seed, plates, terrainSettings);

		TemperatureField temperature = new TemperatureField(seed, climateSettings);
		WindField wind = new WindField(climateSettings, tectonic);

		MoistureScale moisture = new MoistureScale(
				climateSettings, moistureSettings, temperature, wind,
				tectonic, plateSettings.seaLevel());

		// Regions are gated on how much rain actually reaches them, read from the
		// coarse gating lattice. Fixed, never view-dependent: what the ground is made
		// of must not change according to how far the simulator is zoomed out.
		RegionMap regions = new RegionMap(seed, regionSettings,
				RegionClimate.fromPrecipitation(
						moisture.gating(), DRY_RAIN_RATE, WET_RAIN_RATE));

		// Uplift is the budget: crust base, boundary relief and region relief, with
		// nothing eroded away yet. Drainage will route over this, which is why it is
		// its own field rather than something TerrainHeight computes privately.
		UpliftHeight uplift = new UpliftHeight(seed, tectonic, regions, terrainSettings);

		// Tier 1 of drainage: a canonical basin for every point, keyed by the outlet
		// it drains to rather than by the tile that resolved it.
		//
		// Routes tectonics rather than the full uplift surface. At 8,000 blocks it
		// cannot represent region relief anyway, measurement showed the basins come
		// out the same either way, and reading uplift here would drag in the whole
		// moisture field at province scale. Tier 2 routes uplift, where it counts.
		BasinIndex basins = new BasinIndex(tectonic, drainageSettings);

		this.snapshot = new Snapshot(
				plates, regions,
				new TerrainHeight(seed, uplift, terrainSettings),
				temperature, wind, moisture, uplift, basins);
	}
}
