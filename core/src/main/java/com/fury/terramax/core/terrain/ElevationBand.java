package com.fury.terramax.core.terrain;

/**
 * The vertical slice of a chunk that actually needs generating.
 *
 * <p>At 2,048 blocks of height a chunk holds 128 sections. In almost all of them
 * every block is either plain stone or plain air, decided entirely by whether the
 * section sits below or above the terrain. Evaluating density there is wasted
 * work, and at this height it is most of the work.
 *
 * <p>This is not an optimisation in the usual sense of a nicety deferred until
 * profiling. A 5.3x taller world costs 5.3x per chunk without it, and that is the
 * price of the height the design chose.
 *
 * @param minY lowest surface height found in the chunk, already padded
 * @param maxY highest surface height found in the chunk, already padded
 */
public record ElevationBand(int minY, int maxY) {
	/**
	 * Columns sampled per axis when measuring a chunk.
	 *
	 * <p>Sampling all 256 columns would cost as much as generating them. A coarse
	 * grid plus {@code safetyMargin} is enough because the terrain's shortest
	 * wavelength is far longer than a chunk is wide: the surface cannot swing
	 * wildly between samples 4 blocks apart.
	 */
	private static final int SAMPLES_PER_AXIS = 5;

	/**
	 * Measures the band a chunk's terrain occupies.
	 *
	 * @param field          elevation to measure
	 * @param originX        world x of the chunk's lowest corner
	 * @param originZ        world z of the chunk's lowest corner
	 * @param chunkSize      chunk width in blocks, normally 16
	 * @param belowPadding   blocks kept below the lowest surface, for caves
	 * @param abovePadding   blocks kept above the highest surface, for overhangs
	 * @param safetyMargin   extra blocks either side, covering surface movement
	 *                       between the coarse samples
	 */
	public static ElevationBand forChunk(
			final HeightField field,
			final int originX, final int originZ,
			final int chunkSize,
			final int belowPadding, final int abovePadding,
			final int safetyMargin) {
		double lowest = Double.MAX_VALUE;
		double highest = -Double.MAX_VALUE;

		double step = chunkSize / (double) (SAMPLES_PER_AXIS - 1);

		for (int sz = 0; sz < SAMPLES_PER_AXIS; sz++) {
			for (int sx = 0; sx < SAMPLES_PER_AXIS; sx++) {
				double height = field.heightAt(originX + sx * step, originZ + sz * step);

				lowest = Math.min(lowest, height);
				highest = Math.max(highest, height);
			}
		}

		return new ElevationBand(
				(int) Math.floor(lowest) - belowPadding - safetyMargin,
				(int) Math.ceil(highest) + abovePadding + safetyMargin);
	}

	/** True if a section's vertical range overlaps the band and must be generated. */
	public boolean intersects(final int sectionMinY, final int sectionMaxY) {
		return sectionMaxY >= minY && sectionMinY <= maxY;
	}

	/** True if a section sits entirely below the band, so it is solid throughout. */
	public boolean isEntirelyBelow(final int sectionMaxY) {
		return sectionMaxY < minY;
	}

	/** True if a section sits entirely above the band, so it is air throughout. */
	public boolean isEntirelyAbove(final int sectionMinY) {
		return sectionMinY > maxY;
	}

	public int heightBlocks() {
		return maxY - minY;
	}
}
