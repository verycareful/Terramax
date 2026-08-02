package com.fury.terramax.core.terrain;

/**
 * Surface elevation as a function of horizontal position.
 *
 * <p>The seam between the terrain model and everything that looks at it. The
 * simulator plots it, the chunk generator carves to it, and neither needs to know
 * how it is computed.
 *
 * <p>Implementations must be pure functions of position: the same coordinate must
 * always give the same height, with no ordering dependency and no state. A chunk
 * generated on its own must match the same chunk generated as part of a batch.
 */
@FunctionalInterface
public interface HeightField {
	/**
	 * Surface elevation at a horizontal position, in world Y.
	 *
	 * <p>May fall outside the buildable range; clamping is the caller's business,
	 * because clamping here would silently flatten peaks rather than reveal that the
	 * terrain wants more vertical space than the dimension has.
	 */
	double heightAt(double worldX, double worldZ);
}
