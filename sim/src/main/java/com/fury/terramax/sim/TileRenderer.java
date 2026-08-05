package com.fury.terramax.sim;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Renders a map in parallel tiles.
 *
 * <p>At one block per pixel a full-window render is around a million terrain
 * evaluations, each of which now costs three lattice lookups, domain warping and
 * several noise fields. Single-threaded that is tens of seconds.
 *
 * <p>This parallelises perfectly, and the reason is architectural rather than lucky:
 * every pixel is an independent pure function of its coordinate. There is no shared
 * state, no ordering constraint and nothing to lock. That property has been enforced
 * in {@code :core} since the first commit, mostly for determinism, and this is it
 * paying rent.
 *
 * <p>Tiles are handed back as they finish rather than at the end, so a slow render
 * fills in progressively instead of showing nothing for ten seconds.
 */
public final class TileRenderer {
	/**
	 * Tile edge in pixels.
	 *
	 * <p>Small enough that a core is never left idle waiting for stragglers, large
	 * enough that per-tile overhead stays negligible. At 128 a 1024-pixel render is
	 * 64 tiles across a dozen cores, so roughly five rounds.
	 */
	private static final int TILE_PIXELS = 128;

	private static final int WORKERS = Runtime.getRuntime().availableProcessors();

	private TileRenderer() {
	}

	/**
	 * Supplies the colour of one world position.
	 *
	 * <p>Must be thread-safe and a pure function of its arguments. Anything in
	 * {@code :core} already is.
	 */
	@FunctionalInterface
	public interface PixelSource {
		int rgbAt(double worldX, double worldZ);
	}

	/** Notified each time a tile lands, with the whole image so far. */
	@FunctionalInterface
	public interface TileListener {
		void tileReady(BufferedImage image);
	}

	/** Renders everything and returns once complete. */
	public static BufferedImage renderAll(final MapView view, final PixelSource source) {
		return render(view, source, image -> {
		});
	}

	/**
	 * Renders in parallel, notifying {@code listener} as tiles complete.
	 *
	 * <p>The listener receives the shared image rather than a copy, so it must not
	 * retain or mutate it. Swing only reads it during paint, which is safe: a
	 * half-written tile shows as a half-drawn tile, not as corruption.
	 */
	public static BufferedImage render(
			final MapView view, final PixelSource source, final TileListener listener) {
		BufferedImage image = new BufferedImage(
				view.pixels(), view.pixels(), BufferedImage.TYPE_INT_RGB);

		try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
			List<Future<?>> pending = new ArrayList<>();

			for (int originY = 0; originY < view.pixels(); originY += TILE_PIXELS) {
				for (int originX = 0; originX < view.pixels(); originX += TILE_PIXELS) {
					int tileX = originX;
					int tileY = originY;

					pending.add(pool.submit(() -> {
						renderTile(image, view, source, tileX, tileY);
						listener.tileReady(image);
					}));
				}
			}

			awaitAll(pending);
		}

		return image;
	}

	/**
	 * Waits for every tile, surfacing any failure rather than returning a silently
	 * half-rendered image.
	 */
	private static void awaitAll(final List<Future<?>> pending) {
		for (Future<?> task : pending) {
			try {
				task.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException e) {
				throw new IllegalStateException("tile render failed", e.getCause());
			}
		}
	}

	private static void renderTile(
			final BufferedImage image, final MapView view, final PixelSource source,
			final int originX, final int originY) {
		int maxX = Math.min(originX + TILE_PIXELS, view.pixels());
		int maxY = Math.min(originY + TILE_PIXELS, view.pixels());

		for (int py = originY; py < maxY; py++) {
			double worldZ = view.worldZ(py);

			for (int px = originX; px < maxX; px++) {
				// Two tiles never touch the same pixel, so unsynchronised writes to
				// the shared raster are safe.
				image.setRGB(px, py, source.rgbAt(view.worldX(px), worldZ));
			}
		}
	}
}
