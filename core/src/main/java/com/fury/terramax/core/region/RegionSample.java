package com.fury.terramax.core.region;

/**
 * What the region lattice knows about one world position.
 *
 * <p>Carries the neighbour and the boundary distance for the same reason the plate
 * system does: without them the surface steps instantly from one region's height to
 * the next, and every region edge in the world becomes a cliff.
 *
 * <p><b>Blending across that boundary is a placeholder.</b> The full design handles
 * region edges with {@code RIM}, a real transition with its own abruptness, which
 * can be a genuine cliff where a cliff is wanted. Until that exists everything
 * blends smoothly, which is wrong but not ugly.
 *
 * @param region           region owning this position
 * @param neighbour        region across the nearest boundary
 * @param boundaryDistance blocks to that boundary
 */
public record RegionSample(Region region, Region neighbour, double boundaryDistance) {
}
