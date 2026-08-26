package com.fury.terramax.core.terrain;

import com.fury.terramax.core.region.RegionMap;
import com.fury.terramax.core.region.RegionSample;
import com.fury.terramax.core.util.FractalNoise2D;

/**
 * The surface as tectonics and regions would leave it, before any river touches it.
 *
 * <p><b>This exists to break the second cycle, the same way {@link TectonicHeight}
 * broke the first.</b> Drainage has to route over crust base, boundary relief and
 * region relief together, because a 900-block plateau beside a 15-block plain is one
 * of the largest drainage features in a world and a router that could not see it would
 * drive rivers straight across it. But the finished surface is carved <i>from</i>
 * drainage, so drainage cannot read the finished surface.
 *
 * <p>Splitting here resolves it. Climate reads {@link TectonicHeight}, because regions
 * are gated on climate and so climate must come first. Drainage reads this, because
 * nothing gates on drainage except the surface itself. The causal order stays
 * tectonics, topography, climate, erosion.
 *
 * <p><b>This is what the design means by uplift being a budget rather than a
 * height.</b> It is the elevation the ground would reach if no river had ever removed
 * anything, and the drainage network decides how much of it survives at any given
 * point. At a channel almost none of it does; at a divide all of it does.
 *
 * <p>Pure function of position, as {@link HeightField} requires.
 */
public final class UpliftHeight implements HeightField {
	private static final long SALT_REGION = 0x27D4EB2F165667C5L;

	private static final int REGION_OCTAVES = 3;

	private final TectonicHeight tectonic;
	private final RegionMap regions;
	private final TerrainSettings settings;
	private final FractalNoise2D regionRelief;

	public UpliftHeight(
			final long seed,
			final TectonicHeight tectonic,
			final RegionMap regions,
			final TerrainSettings settings) {
		this.tectonic = tectonic;
		this.regions = regions;
		this.settings = settings;

		// Unit wavelength, deliberately. Each region type declares its own, so this
		// field is sampled at coordinates already divided by that wavelength rather
		// than being built for any one of them.
		this.regionRelief = FractalNoise2D.standard(seed ^ SALT_REGION, REGION_OCTAVES, 1.0);
	}

	/**
	 * Everything one uplift lookup yields, so callers need not repeat it.
	 *
	 * <p>The plate search inside {@link TectonicHeight} is the expensive part of a
	 * column, and asking separately for the tectonic surface and for the region would
	 * run it twice.
	 */
	public record Sample(TectonicHeight.Sample tectonic, RegionSample region, double height) {
	}

	public TectonicHeight tectonic() {
		return tectonic;
	}

	public RegionMap regions() {
		return regions;
	}

	public double seaLevel() {
		return tectonic.seaLevel();
	}

	public Sample sample(final double worldX, final double worldZ) {
		TectonicHeight.Sample base = tectonic.sample(worldX, worldZ);

		RegionSample region = regions.sample(
				worldX, worldZ, base.plate().crust().crustType());

		double height = base.height()
				+ regionContribution(region, base.interiority(), worldX, worldZ);

		return new Sample(base, region, height);
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		return sample(worldX, worldZ).height();
	}

	/**
	 * The region's contribution: its target height above the crust base, plus relief
	 * within it.
	 *
	 * <p>This replaces the diffuse {@code interiorRelief} noise that used to sit
	 * here. Without something in this position a plate is one flat plain from edge to
	 * edge, since a crust cell's base is a single value and small-scale detail is
	 * invisible across tens of thousands of blocks. Noise filled the gap but could
	 * only make lumps; a region is a coherent area with a type and an identity, which
	 * is what a biome can be placed on.
	 *
	 * <p>Blended toward the neighbouring region by boundary distance, so region edges
	 * are grades rather than cliffs. That blend is a <b>placeholder</b> for
	 * {@code RIM}, which will handle edges properly and can produce a genuine cliff
	 * where one is wanted.
	 *
	 * <p>Faded by {@code interiority} for the same two reasons the old noise was.
	 * Physically, boundary processes dominate near a margin and inherited interior
	 * structure does not. Practically, stacking full-amplitude relief on a base
	 * already blended toward an ocean neighbour drove terrain through the floor of
	 * the world, and stacking it under a 1,400-block range drove it through the
	 * ceiling.
	 */
	private double regionContribution(
			final RegionSample sample, final double interiority,
			final double worldX, final double worldZ) {
		double blend = TectonicHeight.smoothstep(
				sample.boundaryDistance() / regions.settings().blendWidthBlocks());

		double own = sample.region().targetHeight();
		double neighbour = sample.neighbour().targetHeight();
		double midpoint = (own + neighbour) * 0.5;
		double target = midpoint + (own - midpoint) * blend;

		double wavelength = sample.region().type().wavelength()
				* settings.regionReliefWavelengthFactor();
		double relief = regionRelief.sample(worldX / wavelength, worldZ / wavelength)
				* sample.region().reliefAmplitude();

		return (target + relief) * interiority;
	}
}
