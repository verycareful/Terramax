package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;
import com.fury.terramax.core.util.FractalNoise2D;

/**
 * The surface elevation of a Terramax world.
 *
 * <p>Composed in three layers, in order:
 *
 * <ol>
 *   <li><b>Plate base.</b> Each plate's own height, blended smoothly into its
 *       neighbour near their shared boundary.
 *   <li><b>Boundary relief.</b> Mountains, arcs, trenches and rifts from
 *       {@link MountainRidge}.
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
	private static final long SALT_INTERIOR = 0x27D4EB2F165667C5L;

	private static final int DETAIL_OCTAVES = 4;
	private static final int VALLEY_OCTAVES = 3;
	private static final int INTERIOR_OCTAVES = 3;

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

	private final PlateMap plates;
	private final TerrainSettings settings;
	private final MountainRidge ridge;
	private final FractalNoise2D detail;
	private final FractalNoise2D valleys;
	private final FractalNoise2D interiorRelief;
	private final double blendWidthBlocks;
	private final double seaLevel;

	public TerrainHeight(final long seed, final PlateMap plates, final TerrainSettings settings) {
		this.plates = plates;
		this.settings = settings;
		this.ridge = new MountainRidge(seed, settings, plates.settings().crustSpacingBlocks());
		this.detail = FractalNoise2D.standard(seed ^ SALT_DETAIL, DETAIL_OCTAVES, settings.detailWavelength());
		this.valleys = FractalNoise2D.standard(seed ^ SALT_VALLEY, VALLEY_OCTAVES, settings.valleyWavelength());
		this.interiorRelief = FractalNoise2D.standard(
				seed ^ SALT_INTERIOR,
				INTERIOR_OCTAVES,
				plates.settings().crustSpacingBlocks() * settings.interiorReliefFraction());
		this.blendWidthBlocks = settings.blendWidthBlocks(plates.settings().crustSpacingBlocks());
		this.seaLevel = plates.settings().seaLevel();
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		PlateSample sample = plates.sample(worldX, worldZ);

		// How far into the plate's interior this position is: 0 at a boundary,
		// 1 once past the blend width. Both the base blend and interior relief use
		// it, so they stay consistent with each other.
		double interiority = smoothstep(sample.boundaryDistance() / blendWidthBlocks);

		double height = blendedBase(sample, interiority);
		height += interiorRelief(sample, interiority, worldX, worldZ);
		height += ridge.offsetAt(sample, worldX, worldZ);
		height += landDetail(height, worldX, worldZ);

		return height;
	}

	/**
	 * Uplands and lowlands within a single plate.
	 *
	 * <p>Without this a plate is one flat plain from edge to edge, since its base is
	 * a single value and small-scale detail is invisible across tens of thousands of
	 * blocks. Real continental interiors are not flat: they carry plateaus, basins
	 * and worn-down ranges inherited from tectonics long finished. This stands in for
	 * that history rather than simulating it.
	 *
	 * <p>Applied to continental plates only. Abyssal plains genuinely are flat, and
	 * giving ocean floor the same treatment produces a lumpy seabed that reads as
	 * noise rather than geology.
	 *
	 * <p>Faded out toward boundaries by {@code interiority}. Two reasons. Physically,
	 * boundary processes dominate near a margin and inherited interior structure does
	 * not. Practically, stacking full-amplitude relief on top of a base already
	 * blended toward an ocean neighbour drove terrain through the floor of the world,
	 * and stacking it under a 1,400-block range drove it through the ceiling.
	 */
	private double interiorRelief(
			final PlateSample sample, final double interiority,
			final double worldX, final double worldZ) {
		if (!sample.crust().isContinental()) {
			return 0.0;
		}

		return interiorRelief.sample(worldX, worldZ)
				* settings.interiorReliefAmplitude()
				* interiority;
	}

	/**
	 * Plate base elevation, blended into the neighbour's near the boundary.
	 *
	 * <p>Without this the surface steps instantly from one plate's height to the
	 * next, producing a cliff along every boundary in the world. At the boundary
	 * itself both plates evaluate to the same midpoint, so the surface is continuous
	 * across it regardless of which side is queried.
	 */
	private double blendedBase(final PlateSample sample, final double interiority) {
		double own = sample.crust().baseElevation();
		double neighbour = sample.neighbourCrust().baseElevation();
		double midpoint = (own + neighbour) * 0.5;

		return midpoint + (own - midpoint) * interiority;
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

	private static double smoothstep(final double x) {
		double t = Math.max(0.0, Math.min(1.0, x));

		return t * t * (3.0 - 2.0 * t);
	}
}
