package com.fury.terramax.core.region;

/**
 * A terrain type: a shape, and the height band it occupies.
 *
 * <p>A terrain type is not a biome. {@code PLAIN} carries plains, forest, desert,
 * savanna and steppe; which one appears is climate's business. Terrain decides the
 * shape, climate decides the cover, and a biome is where the two cross. That is
 * why this list is short and the biome list is long.
 *
 * <p><b>Amplitude and wavelength together do more work than either alone.</b>
 * {@code ROLLING_HILLS} and badlands have roughly the same amplitude; badlands pack
 * it into a tenth of the horizontal distance, and that one ratio is the entire
 * difference between the Cotswolds and the Dakota breaks.
 *
 * <p><b>Only the types with no unbuilt dependencies are here.</b> Badlands need
 * lithology and rainfall, karst needs limestone, moraine and lake land need the
 * past-ice extent, and none of those systems exist yet. Adding them now would mean
 * placing them by hash alone, which puts karst towers in granite and lake country
 * in the tropics. They arrive with the fields that gate them.
 */
public enum RegionType {
	/** The real plains. Broad, flat, and where most players will spend their time. */
	PLAIN(5, 15, 6, 2_000, 30),

	/** Worn-down relief with mature drainage. Long wavelength, gentle. */
	ROLLING_HILLS(15, 100, 45, 1_400, 22),

	/** A lifted flat surface. Continuous from 100 upward; Tibet is the high end. */
	PLATEAU(100, 900, 25, 3_000, 10),

	/** Small mountain, sedimentary. Rounded and stepped rather than jagged. */
	HILL(100, 400, 140, 900, 12),

	/** Flat with isolated steep domes shoved through it. Uluru, the Sahel. */
	INSELBERG_PLAIN(10, 40, 90, 700, 6),

	/** Flat tops at a common level, steep sides, flat floor between. Monument Valley. */
	MESA(150, 500, 110, 1_100, 6),

	/** Everything under the sea. A single type until oceans matter. */
	OCEAN_FLOOR(-200, -60, 30, 4_000, 1);

	private final int minHeight;
	private final int maxHeight;
	private final int reliefAmplitude;
	private final int wavelength;
	private final int weight;

	RegionType(final int minHeight, final int maxHeight,
			final int reliefAmplitude, final int wavelength, final int weight) {
		this.minHeight = minHeight;
		this.maxHeight = maxHeight;
		this.reliefAmplitude = reliefAmplitude;
		this.wavelength = wavelength;
		this.weight = weight;
	}

	/** Lowest target elevation for a region of this type, in blocks. */
	public int minHeight() {
		return minHeight;
	}

	/** Highest target elevation for a region of this type, in blocks. */
	public int maxHeight() {
		return maxHeight;
	}

	/** Vertical spread within the region, in blocks. */
	public int reliefAmplitude() {
		return reliefAmplitude;
	}

	/** Horizontal scale of that relief, in blocks. */
	public int wavelength() {
		return wavelength;
	}

	/** Relative frequency when a region rolls its type. */
	public int weight() {
		return weight;
	}

	/**
	 * Types available on continental crust.
	 *
	 * <p>Oceanic crust gets {@link #OCEAN_FLOOR} and nothing else, so it needs no
	 * table of its own.
	 */
	public static final RegionType[] CONTINENTAL = {
		PLAIN, ROLLING_HILLS, PLATEAU, HILL, INSELBERG_PLAIN, MESA
	};

	/** Total weight of {@link #CONTINENTAL}, for the weighted draw. */
	public static int continentalWeightSum() {
		int sum = 0;

		for (RegionType type : CONTINENTAL) {
			sum += type.weight;
		}

		return sum;
	}
}
