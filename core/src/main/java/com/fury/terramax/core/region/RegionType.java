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
 * <p><b>Heights are offsets above the crust platform, not absolute elevations.</b>
 * The crust cell supplies a base, near 0 for continent and well below for ocean, and
 * a region's band is measured from there. Reading these as absolute elevations and
 * adding them to a base that already carried the land/ocean split drove the seafloor
 * to y=-367, through the floor of the world.
 *
 * <p><b>Each type carries two weights, one humid and one arid.</b> Climate does not
 * choose landforms; it chooses which ones survive. Rolling hills are what an
 * escarpment becomes under rain, and a mesa is an escarpment that never got rained on
 * enough to be cut through. See {@link RegionClimate} for why that framing, rather
 * than "deserts get mesas", is the one that produces a coherent map.
 *
 * <p><b>Only the types with no unbuilt dependencies are here.</b> Badlands need
 * lithology and rainfall, karst needs limestone, moraine and lake land need the
 * past-ice extent, and none of those systems exist yet. Adding them now would mean
 * placing them by hash alone, which puts karst towers in granite and lake country
 * in the tropics. They arrive with the fields that gate them.
 */
public enum RegionType {
	/**
	 * The real plains. Broad, flat, and where most players will spend their time.
	 *
	 * <p>The one genuinely climate-neutral landform, and the only type with equal
	 * weights. Flat ground stays flat whether it rains on it or not.
	 */
	PLAIN(5, 15, 6, 2_000, 30, 30),

	/**
	 * Worn-down relief with mature drainage. Long wavelength, gentle.
	 *
	 * <p>The humid endpoint. Rolling hills are not a thing that forms; they are what
	 * everything else becomes once enough water has run over it for long enough.
	 */
	ROLLING_HILLS(15, 100, 45, 1_400, 30, 8),

	/** A lifted flat surface. Continuous from 100 upward; Tibet is the high end. */
	PLATEAU(100, 900, 25, 3_000, 10, 13),

	/**
	 * Small mountain, sedimentary. Rounded and stepped rather than jagged.
	 *
	 * <p>Leans humid: the rounding is the giveaway. In an arid climate the same
	 * uplifted sediment keeps its edges and reads as mesa or inselberg instead.
	 */
	HILL(100, 400, 140, 900, 16, 6),

	/** Flat with isolated steep domes shoved through it. Uluru, the Sahel. */
	INSELBERG_PLAIN(10, 40, 90, 700, 2, 14),

	/**
	 * Flat tops at a common level, steep sides, flat floor between. Monument Valley.
	 *
	 * <p>The arid endpoint, and the type that most needs the gate. A mesa is a
	 * caprock escarpment that has not been cut through, so it can only persist where
	 * there is not enough runoff to cut it. Under rain it is a hill within a
	 * geological blink.
	 */
	MESA(150, 500, 110, 1_100, 1, 18),

	/**
	 * Everything under the sea. A single type until oceans matter.
	 *
	 * <p>A narrow band because the abyssal plain genuinely is flat, and because the
	 * oceanic crust base already puts it deep. Giving the seabed the same spread as
	 * land produces a lumpy floor that reads as noise rather than geology.
	 */
	OCEAN_FLOOR(-30, 30, 25, 4_000, 1, 1);

	private final int minHeight;
	private final int maxHeight;
	private final int reliefAmplitude;
	private final int wavelength;
	private final int humidWeight;
	private final int aridWeight;

	RegionType(final int minHeight, final int maxHeight,
			final int reliefAmplitude, final int wavelength,
			final int humidWeight, final int aridWeight) {
		this.minHeight = minHeight;
		this.maxHeight = maxHeight;
		this.reliefAmplitude = reliefAmplitude;
		this.wavelength = wavelength;
		this.humidWeight = humidWeight;
		this.aridWeight = aridWeight;
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

	/** Relative frequency where erosion is fastest. */
	public int humidWeight() {
		return humidWeight;
	}

	/** Relative frequency where nothing wears down. */
	public int aridWeight() {
		return aridWeight;
	}

	/**
	 * Relative frequency at a given dryness, 0 humid to 1 arid.
	 *
	 * <p>Interpolated rather than switched, so a type fades out across a climate
	 * gradient instead of stopping at a line. A hard cutoff would draw the climate
	 * bands onto the terrain map as visible edges, which is the patchwork again in a
	 * different orientation.
	 */
	public double weightAt(final double aridity) {
		return humidWeight + (aridWeight - humidWeight) * aridity;
	}

	/**
	 * Types available on continental crust, ordered as a landform axis.
	 *
	 * <p><b>The order is load-bearing.</b> A region's type is drawn from a smooth
	 * field rather than an independent roll, so entries adjacent in this array end up
	 * adjacent on the ground wherever the field crosses between them. Ordering it
	 * arbitrarily would put floodplains against mesas along every province edge.
	 *
	 * <p>Flat, to gently rolling, to hilly, to high and flat, to tabled, to domed.
	 * The two arid types sit together at the far end, so mesa country borders
	 * inselberg country, which is what the Colorado Plateau and the Australian
	 * interior actually look like.
	 */
	public static final RegionType[] CONTINENTAL = {
		PLAIN, ROLLING_HILLS, HILL, PLATEAU, MESA, INSELBERG_PLAIN
	};

	/** Total weight of {@link #CONTINENTAL} at a given dryness, for the weighted draw. */
	public static double continentalWeightSum(final double aridity) {
		double sum = 0.0;

		for (RegionType type : CONTINENTAL) {
			sum += type.weightAt(aridity);
		}

		return sum;
	}
}
