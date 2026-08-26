package com.fury.terramax.sim;

import java.util.concurrent.ConcurrentHashMap;

import com.fury.terramax.core.climate.ClimateSettings;
import com.fury.terramax.core.climate.MoistureField;
import com.fury.terramax.core.climate.MoistureSettings;
import com.fury.terramax.core.climate.SurfaceClimate;
import com.fury.terramax.core.climate.TemperatureField;
import com.fury.terramax.core.climate.WindField;
import com.fury.terramax.core.terrain.HeightField;

/**
 * The moisture field, solved at whatever lattice a given view can afford.
 *
 * <p><b>Why this exists.</b> A moisture node is a forty-step trajectory and about two
 * hundred uplift evaluations, and the game amortises that over the thousand columns
 * between nodes. The simulator cannot: at a planetary span of five million blocks each
 * pixel is over eight hundred blocks wide, so every pixel would land on its own node
 * and the render would take hours.
 *
 * <p>The fix is not to change the model. It is to notice that the lattice spacing is a
 * setting, and that solving on a coarser lattice than the screen can resolve loses
 * nothing visible. Nodes are held no further apart than {@link #PIXELS_PER_NODE}
 * pixels, which bounds the cost per pixel no matter how far out the view is zoomed.
 *
 * <p>This keeps the one-copy-of-the-maths rule intact. Every instance here is the same
 * {@link MoistureField} class running the same trace; only the grid it is sampled on
 * differs, and {@link #canonical()} is always available for anything that must match
 * the game exactly.
 *
 * <p>Spacings double from the canonical one rather than taking arbitrary values, so a
 * pan or a small zoom keeps hitting the same instance and its cache stays warm.
 */
public final class MoistureScale {
	/** Coarsest a node may be relative to a pixel, before the lattice is doubled. */
	private static final double PIXELS_PER_NODE = 3.0;

	private final MoistureSettings base;
	private final ConcurrentHashMap<Double, MoistureField> byLattice = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Double, SurfaceClimate> surfaces = new ConcurrentHashMap<>();

	private final ClimateSettings climate;
	private final TemperatureField temperature;
	private final WindField wind;
	private final HeightField uplift;
	private final double seaLevel;

	public MoistureScale(
			final ClimateSettings climate, final MoistureSettings base,
			final TemperatureField temperature, final WindField wind,
			final HeightField uplift, final double seaLevel) {
		this.climate = climate;
		this.base = base;
		this.temperature = temperature;
		this.wind = wind;
		this.uplift = uplift;
		this.seaLevel = seaLevel;
	}

	/** The lattice the game generates on. Probes and statistics use this one. */
	public MoistureField canonical() {
		return atLattice(base.latticeSpacingBlocks());
	}

	/**
	 * The coarse lattice region gating is solved on.
	 *
	 * <p>Fixed, never view-dependent. What the ground is made of must not change
	 * according to how far the simulator happens to be zoomed out, so this cannot go
	 * through {@link #forResolution}.
	 */
	public MoistureField gating() {
		return atLattice(base.gatingLatticeBlocks());
	}

	/** The finest lattice worth solving for a view at this resolution. */
	public MoistureField forResolution(final double blocksPerPixel) {
		return atLattice(latticeFor(blocksPerPixel));
	}

	/** Node spacing chosen for a view at this resolution, in blocks. */
	public double latticeFor(final double blocksPerPixel) {
		double wanted = blocksPerPixel * PIXELS_PER_NODE;
		double spacing = base.latticeSpacingBlocks();

		while (spacing < wanted) {
			spacing *= 2.0;
		}

		return spacing;
	}

	/** Temperature with the air's history counted, at the canonical lattice. */
	public SurfaceClimate surfaceClimate() {
		return surfaceAt(base.latticeSpacingBlocks());
	}

	/** The same, solved no finer than a view at this resolution can show. */
	public SurfaceClimate surfaceFor(final double blocksPerPixel) {
		return surfaceAt(latticeFor(blocksPerPixel));
	}

	private SurfaceClimate surfaceAt(final double spacing) {
		return surfaces.computeIfAbsent(spacing,
				blocks -> new SurfaceClimate(climate, temperature, atLattice(blocks)));
	}

	private MoistureField atLattice(final double spacing) {
		return byLattice.computeIfAbsent(spacing, blocks -> new MoistureField(
				climate, base.withLatticeSpacing(blocks),
				temperature, wind, uplift, seaLevel));
	}
}
