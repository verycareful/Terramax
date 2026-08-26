package com.fury.terramax.core.terrain;

import com.fury.terramax.core.util.FractalNoise2D;

/**
 * The surface elevation of a Terramax world.
 *
 * <p>Composed in two layers, in order:
 *
 * <ol>
 *   <li><b>Uplift.</b> Crust base, boundary relief and region relief, from
 *       {@link UpliftHeight}. The elevation the ground would reach if nothing had
 *       ever eroded it.
 *   <li><b>Erosion detail.</b> Small-scale roughness and valley carving, applied
 *       only to land, since dragging channels across the abyssal plain looks wrong.
 * </ol>
 *
 * <p>Pure function of position, as {@link HeightField} requires. No state, no
 * ordering dependency, so a chunk generated alone matches the same chunk
 * generated in a batch.
 */
public final class TerrainHeight implements HeightField {
	private static final long SALT_DETAIL = 0xD1B54A32D192ED03L;
	private static final long SALT_VALLEY = 0xAEF17502108EF2D9L;

	private static final int DETAIL_OCTAVES = 4;
	private static final int VALLEY_OCTAVES = 3;

	/**
	 * How sharply valleys narrow. Higher concentrates carving into fewer, tighter
	 * channels; 1.0 would dish out the whole landscape instead of cutting valleys.
	 */
	private static final double VALLEY_SHARPNESS = 3.0;

	/**
	 * Height above sea level at which land detail reaches full strength.
	 *
	 * <p>Fading detail in over the first stretch of elevation keeps coastlines from
	 * fragmenting into noise, which happens when full-amplitude roughness is applied
	 * to ground sitting a few blocks above the waterline.
	 */
	private static final double DETAIL_RAMP_BLOCKS = 120.0;

	private final UpliftHeight uplift;
	private final TerrainSettings settings;
	private final FractalNoise2D detail;
	private final FractalNoise2D valleys;
	private final double seaLevel;

	public TerrainHeight(
			final long seed,
			final UpliftHeight uplift,
			final TerrainSettings settings) {
		this.uplift = uplift;
		this.settings = settings;
		this.detail = FractalNoise2D.standard(seed ^ SALT_DETAIL, DETAIL_OCTAVES, settings.detailWavelength());
		this.valleys = FractalNoise2D.standard(seed ^ SALT_VALLEY, VALLEY_OCTAVES, settings.valleyWavelength());

		this.seaLevel = uplift.seaLevel();
	}

	public UpliftHeight uplift() {
		return uplift;
	}

	public TectonicHeight tectonic() {
		return uplift.tectonic();
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		double height = uplift.heightAt(worldX, worldZ);

		return height + landDetail(height, worldX, worldZ);
	}

	/**
	 * Roughness and valley carving, applied to land only and faded in with altitude.
	 */
	private double landDetail(final double height, final double worldX, final double worldZ) {
		double aboveSea = height - seaLevel;

		if (aboveSea <= 0.0) {
			return 0.0;
		}

		double strength = Math.min(1.0, aboveSea / DETAIL_RAMP_BLOCKS);

		double roughness = detail.sample(worldX, worldZ) * settings.detailAmplitude();

		// Valleys sit where the field crosses zero, so the channels form a connected
		// branching network rather than isolated pits.
		double channel = Math.pow(1.0 - Math.min(1.0, Math.abs(valleys.sample(worldX, worldZ))),
				VALLEY_SHARPNESS);
		double carve = -settings.valleyDepth() * channel;

		return (roughness + carve) * strength;
	}
}
