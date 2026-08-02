package com.fury.terramax.sim;

/**
 * A square window onto the world, and the resolution to sample it at.
 *
 * @param centreX     world x at the centre of the image
 * @param centreZ     world z at the centre of the image
 * @param spanBlocks  width and height of the window, in blocks
 * @param pixels      width and height of the output image
 */
public record MapView(double centreX, double centreZ, double spanBlocks, int pixels) {
	public MapView {
		if (spanBlocks <= 0.0) {
			throw new IllegalArgumentException("spanBlocks must be positive, got " + spanBlocks);
		}

		if (pixels <= 0) {
			throw new IllegalArgumentException("pixels must be positive, got " + pixels);
		}
	}

	/** Blocks covered by one pixel. */
	public double blocksPerPixel() {
		return spanBlocks / pixels;
	}

	public double worldX(final int pixelX) {
		return centreX - spanBlocks * 0.5 + (pixelX + 0.5) * blocksPerPixel();
	}

	public double worldZ(final int pixelZ) {
		return centreZ - spanBlocks * 0.5 + (pixelZ + 0.5) * blocksPerPixel();
	}
}
