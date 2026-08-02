package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;

/**
 * Elevation from plate identity alone: each plate sits flat at its own base
 * height.
 *
 * <p>A placeholder, and deliberately a crude one. There are no mountains at
 * convergent boundaries, no rifts at divergent ones, and no erosion. Crossing a
 * boundary steps instantly from one plate's height to the next, which is why a
 * cross-section through this looks like a bar chart.
 *
 * <p>It exists so the cross-section plotter and the interactive viewer can be
 * built and verified against something real before the terrain functions land in
 * step 6, at which point this is replaced by their composition.
 */
public final class PlateBaseHeight implements HeightField {
	private final PlateMap plates;

	public PlateBaseHeight(final PlateMap plates) {
		this.plates = plates;
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		return plates.sample(worldX, worldZ).plate().baseElevation();
	}
}
