package com.fury.terramax.sim;

import com.fury.terramax.core.climate.ClimateSettings;
import com.fury.terramax.core.climate.MoistureSettings;
import com.fury.terramax.core.climate.TemperatureField;
import com.fury.terramax.core.climate.WindField;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateMapSettings;
import com.fury.terramax.core.region.RegionMap;
import com.fury.terramax.core.region.RegionSettings;
import com.fury.terramax.core.terrain.TerrainHeight;
import com.fury.terramax.core.terrain.TerrainSettings;

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
	private long seed;
	private PlateMapSettings plateSettings;
	private RegionSettings regionSettings;
	private TerrainSettings terrainSettings;
	private ClimateSettings climateSettings;
	private MoistureSettings moistureSettings;

	private Snapshot snapshot;

	public TerrainModel(final long seed) {
		this.seed = seed;
		this.plateSettings = PlateMapSettings.defaults();
		this.regionSettings = RegionSettings.defaults();
		this.terrainSettings = TerrainSettings.defaults();
		this.climateSettings = ClimateSettings.defaults();
		this.moistureSettings = MoistureSettings.defaults();

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
			TemperatureField temperature, WindField wind, MoistureScale moisture) {
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

	private void rebuild() {
		PlateMap plates = new PlateMap(seed, plateSettings);
		RegionMap regions = new RegionMap(seed, regionSettings);

		TerrainHeight terrain = new TerrainHeight(seed, plates, regions, terrainSettings);

		TemperatureField temperature = new TemperatureField(seed, climateSettings);

		// Wind reads the uplift layer, not the finished surface. That is what keeps
		// the pipeline acyclic once drainage lands: terrain depends on rivers, rivers
		// on moisture, moisture on wind, wind on terrain.
		WindField wind = new WindField(climateSettings, terrain::upliftAt);

		this.snapshot = new Snapshot(
				plates, regions, terrain, temperature, wind,
				new MoistureScale(
						climateSettings, moistureSettings, temperature, wind,
						terrain::upliftAt, plateSettings.seaLevel()));
	}
}
