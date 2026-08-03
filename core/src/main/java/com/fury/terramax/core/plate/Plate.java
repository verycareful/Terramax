package com.fury.terramax.core.plate;

/**
 * A tectonic plate: an identity and a motion vector.
 *
 * <p>Deliberately thin. A plate no longer carries crust type or elevation,
 * because both are now properties of the crust cells it owns rather than of the
 * plate itself. What remains is what a plate actually is at this scale: something
 * that moves as a unit, and therefore something that can converge with, diverge
 * from, or slide past its neighbours.
 *
 * @param cellX   nucleus lattice column identifying this plate
 * @param cellZ   nucleus lattice row identifying this plate
 * @param motionX x component of motion, magnitude in [0, 1]
 * @param motionZ z component of motion, magnitude in [0, 1]
 */
public record Plate(long cellX, long cellZ, double motionX, double motionZ) {
}
