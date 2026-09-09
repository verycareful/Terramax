package com.fury.terramax.sim;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The map itself: pan, zoom, render, and whatever the current mode does with a
 * click.
 *
 * <p>Rendering happens off the event thread through {@link TileRenderer}, with tiles
 * painted as they land, and it climbs a <b>refinement ladder</b>: 96 pixels first,
 * then 192, 384, 768, and finally the panel's own size. Every level is displayed as it
 * lands and each costs four times the one before, so the first image arrives in about
 * the time it takes to notice you asked for it, and sharpens while you look at it.
 *
 * <p><b>Cost lives in the sample count, not in the zoom.</b> That is the whole reason
 * this exists. The panel used to render at {@code min(width, height)} pixels whatever
 * the span, so every view cost around a million terrain columns whether it covered a
 * thousand blocks or a million, and the window opened by evaluating all of them before
 * drawing anything. Capping samples rather than zoom makes a wide view cost exactly
 * what a close one costs.
 *
 * <p>Dragging needs no special case, which is the pleasant part. Each mouse event
 * bumps the generation, cancelling the climb in flight and starting a new one at 96
 * pixels, so a drag naturally shows a coarse image that tracks the mouse. Stop moving
 * and the last climb runs to the top on its own.
 *
 * <p>Knows nothing about controls, statistics or the section plot. It reports what
 * happened through {@link MapListener} and lets the frame decide what to do about it.
 */
public final class MapPanel extends JPanel {
	/**
	 * Resolutions the ladder climbs through, in pixels per axis.
	 *
	 * <p>Doubling, so each level costs four times the last and the whole climb costs
	 * about a third more than the top level alone. Anything below 96 is too coarse to
	 * recognise ground by; anything above the panel is wasted.
	 */
	private static final int[] LADDER = {96, 192, 384, 768};

	private static final double ZOOM_STEP = 1.25;

	/**
	 * Tightest zoom, in blocks across the window.
	 *
	 * <p>256 blocks in a 1,000-pixel window is about four pixels per block. Terrain
	 * has no detail below one block, so zooming further only shows larger squares of
	 * the same information.
	 */
	private static final double MIN_SPAN_BLOCKS = 256.0;

	private static final double MAX_SPAN_BLOCKS = 1.0e8;

	private static final Color BACKGROUND = new Color(18, 20, 24);
	private static final Color OVERLAY_TEXT = new Color(232, 236, 244);
	private static final Color OVERLAY_SHADOW = new Color(0, 0, 0, 170);
	private static final Color PICK_MARKER = new Color(255, 214, 64);

	/** Scale bar aims for roughly this fraction of the window width. */
	private static final double SCALE_BAR_TARGET_FRACTION = 0.22;

	private static final int SCALE_BAR_MARGIN = 18;
	private static final int PICK_MARKER_RADIUS = 5;

	/** Reports what the user did, so the frame can update the panels around it. */
	public interface MapListener {
		void cursorMoved(double worldX, double worldZ);

		void cursorLeft();

		void sectionDrawn(double startX, double startZ, double endX, double endZ);

		void pointProbed(double worldX, double worldZ);

		/**
		 * One level of the ladder finished and is on screen.
		 *
		 * @param view      what was rendered, including the resolution it was rendered at
		 * @param elapsedMs how long this level took
		 * @param level     one-based position on the ladder
		 * @param levels    how many levels this climb will run, so a caller can say
		 *                  "3 of 5" rather than leaving the user guessing whether the
		 *                  picture is finished
		 */
		void renderComplete(MapView view, long elapsedMs, int level, int levels);
	}

	private final transient TerrainModel model;
	private final transient MapListener listener;

	private transient MapRenderer.Layer plateLayer = MapRenderer.Layer.CRUST_TYPE;
	private transient MapRenderer.TerrainLayer terrainLayer = MapRenderer.TerrainLayer.ELEVATION_MAGMA;
	private transient ViewerMode mode = ViewerMode.PAN;

	/** First endpoint of a pending section, in world coordinates, or null. */
	private transient double[] pendingSection;

	private double centreX;
	private double centreZ;
	private double spanBlocks;

	private transient BufferedImage current;
	private transient double currentSpan;

	/**
	 * Bumped by anything that changes what should be on screen.
	 *
	 * <p>A climb renders only while the generation it started under is still current,
	 * so a view change abandons it. Every tile checks this twice, and the check before
	 * publishing is the one that matters: without it an abandoned render would paint
	 * its last finished tiles over the new view.
	 */
	private final transient AtomicInteger generation = new AtomicInteger();

	/** Held by whichever thread is climbing, so there is never more than one. */
	private final transient AtomicBoolean rendering = new AtomicBoolean(false);

	private int dragOriginX;
	private int dragOriginY;

	public MapPanel(
			final TerrainModel model, final MapListener listener, final double initialSpanBlocks) {
		this.model = model;
		this.listener = listener;
		this.spanBlocks = initialSpanBlocks;

		setBackground(BACKGROUND);
		installMouseHandlers();
	}

	public void setMode(final ViewerMode newMode) {
		this.mode = newMode;
		this.pendingSection = null;
		repaint();
	}

	public ViewerMode mode() {
		return mode;
	}

	/** Selects a plate layer, clearing any terrain layer. Exactly one is ever active. */
	public void setLayer(final MapRenderer.Layer layer) {
		this.plateLayer = layer;
		this.terrainLayer = null;
		requestRender();
	}

	/** Selects a terrain layer, which takes precedence over the plate layer. */
	public void setTerrainLayer(final MapRenderer.TerrainLayer layer) {
		this.terrainLayer = layer;
		requestRender();
	}

	public MapRenderer.TerrainLayer terrainLayer() {
		return terrainLayer;
	}

	public MapRenderer.Layer plateLayer() {
		return plateLayer;
	}

	/** Call after any settings change, so the view picks up the rebuilt world. */
	public void refresh() {
		requestRender();
	}

	/** Centres the view on a coordinate without changing the zoom. */
	public void goTo(final double worldX, final double worldZ) {
		this.centreX = worldX;
		this.centreZ = worldZ;
		requestRender();
	}

	public double centreX() {
		return centreX;
	}

	public double centreZ() {
		return centreZ;
	}

	public double spanBlocks() {
		return spanBlocks;
	}

	public double worldXAt(final int pixelX) {
		return centreX + (pixelX - getWidth() / 2.0) * blocksPerPixel();
	}

	public double worldZAt(final int pixelY) {
		return centreZ + (pixelY - getHeight() / 2.0) * blocksPerPixel();
	}

	public double blocksPerPixel() {
		return spanBlocks / Math.max(1, getWidth());
	}

	private void installMouseHandlers() {
		MouseAdapter handler = new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				dragOriginX = e.getX();
				dragOriginY = e.getY();
			}

			@Override
			public void mouseDragged(final MouseEvent e) {
				// Panning stays available in every mode. A tool that also disabled
				// navigation would mean leaving the tool to look somewhere else.
				centreX -= (e.getX() - dragOriginX) * blocksPerPixel();
				centreZ -= (e.getY() - dragOriginY) * blocksPerPixel();

				dragOriginX = e.getX();
				dragOriginY = e.getY();

				requestRender();
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				requestRender();
			}

			@Override
			public void mouseMoved(final MouseEvent e) {
				listener.cursorMoved(worldXAt(e.getX()), worldZAt(e.getY()));
			}

			@Override
			public void mouseExited(final MouseEvent e) {
				listener.cursorLeft();
			}

			@Override
			public void mouseClicked(final MouseEvent e) {
				if (!SwingUtilities.isLeftMouseButton(e)) {
					return;
				}

				handleClick(worldXAt(e.getX()), worldZAt(e.getY()));
			}

			@Override
			public void mouseWheelMoved(final MouseWheelEvent e) {
				// Zoom about the cursor rather than the centre, so the point under the
				// pointer stays put. Zooming about the centre makes it impossible to
				// close in on anything off-centre.
				double anchorX = worldXAt(e.getX());
				double anchorZ = worldZAt(e.getY());

				double factor = e.getWheelRotation() < 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP;
				double newSpan = Math.max(
						MIN_SPAN_BLOCKS, Math.min(MAX_SPAN_BLOCKS, spanBlocks * factor));
				double ratio = newSpan / spanBlocks;

				centreX = anchorX + (centreX - anchorX) * ratio;
				centreZ = anchorZ + (centreZ - anchorZ) * ratio;
				spanBlocks = newSpan;

				requestRender();
			}
		};

		addMouseListener(handler);
		addMouseMotionListener(handler);
		addMouseWheelListener(handler);
	}

	private void handleClick(final double worldX, final double worldZ) {
		switch (mode) {
			case PAN -> {
				// Nothing. Panning is a drag.
			}

			case PROBE -> listener.pointProbed(worldX, worldZ);

			case SECTION -> {
				if (pendingSection == null) {
					pendingSection = new double[] {worldX, worldZ};
					repaint();
					return;
				}

				listener.sectionDrawn(pendingSection[0], pendingSection[1], worldX, worldZ);
				pendingSection = null;
				repaint();
			}
		}
	}

	/**
	 * Marks what is on screen as out of date and starts a climb if none is running.
	 *
	 * <p>Cheap enough to call from a mouse-drag handler, which is the point: coalescing
	 * is not needed because the generation counter makes a superseded climb abandon
	 * itself rather than queueing behind the new one.
	 */
	private void requestRender() {
		generation.incrementAndGet();

		// A climb already in flight will notice the bump and start over, so there is
		// nothing to do but let it. Only start a thread when none holds the flag.
		if (rendering.compareAndSet(false, true)) {
			Thread worker = new Thread(this::climb, "terramax-render");

			worker.setDaemon(true);
			worker.start();
		}
	}

	/**
	 * Climbs the ladder for the current generation, and again if it moved meanwhile.
	 *
	 * <p>The reacquire at the end closes the window between releasing the flag and
	 * exiting. Without it a request arriving in that instant would see the flag held,
	 * decline to start a thread, and then watch this one leave: the view would sit
	 * stale until the user moved the mouse again.
	 */
	private void climb() {
		while (true) {
			int mine = generation.get();

			climbFor(mine);
			rendering.set(false);

			if (generation.get() == mine || !rendering.compareAndSet(false, true)) {
				return;
			}
		}
	}

	/** One full climb, abandoned as soon as the generation moves past {@code mine}. */
	private void climbFor(final int mine) {
		// Read once, after the generation, so these are the values that were current
		// when it was bumped rather than a mixture from two different views.
		double x = centreX;
		double z = centreZ;
		double span = spanBlocks;
		int cap = Math.max(1, Math.min(getWidth(), getHeight()));

		// One snapshot for the whole climb. Taking the maps separately would let a
		// settings change land between them and leave the plate map and the terrain
		// describing different worlds, and taking a fresh one per level would let the
		// picture change meaning as it sharpened.
		TerrainModel.Snapshot world = model.snapshot();

		MapRenderer.Layer requestedPlate = plateLayer;
		MapRenderer.TerrainLayer requestedTerrain = terrainLayer;

		int[] levels = ladderTo(cap);

		for (int step = 0; step < levels.length; step++) {
			if (generation.get() != mine) {
				return;
			}

			MapView view = new MapView(x, z, span, levels[step]);
			long start = System.nanoTime();

			BufferedImage image = renderLevel(world, view, requestedTerrain, requestedPlate,
					() -> generation.get() != mine);

			if (generation.get() != mine) {
				return;
			}

			long elapsed = (System.nanoTime() - start) / 1_000_000L;
			int level = step + 1;
			int count = levels.length;

			SwingUtilities.invokeLater(() -> {
				// Checked again on the event thread. Between the worker's last check and
				// this running, a mouse event may have arrived, and painting here would
				// put an abandoned frame on screen with nothing left to correct it.
				if (generation.get() != mine) {
					return;
				}

				current = image;
				currentSpan = view.spanBlocks();
				listener.renderComplete(view, elapsed, level, count);
				repaint();
			});
		}
	}

	private BufferedImage renderLevel(
			final TerrainModel.Snapshot world, final MapView view,
			final MapRenderer.TerrainLayer terrain, final MapRenderer.Layer plates,
			final TileRenderer.Cancelled cancelled) {
		return terrain != null
				? MapRenderer.renderTerrainProgressive(
						world, view, terrain,
						WorldBounds.MIN_Y, WorldBounds.MAX_Y, WorldBounds.SEA_LEVEL,
						partial -> showPartial(partial, view), cancelled)
				: MapRenderer.renderProgressive(
						world.plates(), view, plates,
						partial -> showPartial(partial, view), cancelled);
	}

	/**
	 * The rungs to climb for a panel of this size, ending at the panel itself.
	 *
	 * <p>Rungs at or above the cap are dropped, so a small panel climbs fewer levels
	 * rather than rendering more pixels than it can show. The cap is always the last
	 * rung, so the climb always finishes at native resolution.
	 */
	private static int[] ladderTo(final int cap) {
		int count = 0;

		while (count < LADDER.length && LADDER[count] < cap) {
			count++;
		}

		int[] levels = new int[count + 1];

		System.arraycopy(LADDER, 0, levels, 0, count);
		levels[count] = cap;

		return levels;
	}

	/**
	 * Shows a partially rendered image without waiting for the rest.
	 *
	 * <p>Tiles still in flight appear as black squares that fill in, which is the
	 * feedback wanted: at one block per pixel a full render takes seconds even across
	 * a dozen cores, and a blank window for that long is indistinguishable from a
	 * hang.
	 */
	private void showPartial(final BufferedImage partial, final MapView view) {
		SwingUtilities.invokeLater(() -> {
			current = partial;
			currentSpan = view.spanBlocks();
			repaint();
		});
	}

	@Override
	protected void paintComponent(final Graphics graphics) {
		super.paintComponent(graphics);

		Graphics2D g = (Graphics2D) graphics;

		if (current == null) {
			requestRender();
			g.setColor(OVERLAY_TEXT);
			g.drawString("rendering...", 16, 26);
			return;
		}

		// Nearest neighbour: a draft frame upscaled smoothly looks like a finished
		// render, which hides that you are looking at a preview.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(current, 0, 0, getWidth(), getHeight(), null);

		drawScaleBar(g);
		drawPendingSection(g);
	}

	/**
	 * A bar of a round number of blocks, so distances on screen can be read directly.
	 *
	 * <p>Rounds down to 1, 2 or 5 times a power of ten. Arbitrary lengths would be
	 * technically accurate and useless: nobody estimates against 37,412 blocks.
	 */
	private void drawScaleBar(final Graphics2D g) {
		double targetBlocks = spanBlocks * SCALE_BAR_TARGET_FRACTION;
		double magnitude = Math.pow(10, Math.floor(Math.log10(targetBlocks)));
		double normalised = targetBlocks / magnitude;

		double niceBlocks = magnitude * (normalised >= 5.0 ? 5.0 : normalised >= 2.0 ? 2.0 : 1.0);
		int barPixels = (int) Math.round(niceBlocks / blocksPerPixel());

		if (barPixels < 20 || barPixels > getWidth()) {
			return;
		}

		int y = getHeight() - SCALE_BAR_MARGIN;
		int x = SCALE_BAR_MARGIN;

		String label = niceBlocks >= 1000
				? String.format("%,.0fk blocks", niceBlocks / 1000.0)
				: String.format("%,.0f blocks", niceBlocks);

		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		g.setColor(OVERLAY_SHADOW);
		g.fillRect(x - 6, y - 22, barPixels + 12, 30);

		g.setColor(OVERLAY_TEXT);
		g.drawLine(x, y, x + barPixels, y);
		g.drawLine(x, y - 4, x, y + 4);
		g.drawLine(x + barPixels, y - 4, x + barPixels, y + 4);
		g.drawString(label, x, y - 8);
	}

	/** Marks the first endpoint of a section, so a half-finished pick is visible. */
	private void drawPendingSection(final Graphics2D g) {
		if (pendingSection == null) {
			return;
		}

		int px = (int) Math.round((pendingSection[0] - centreX) / blocksPerPixel() + getWidth() / 2.0);
		int py = (int) Math.round((pendingSection[1] - centreZ) / blocksPerPixel() + getHeight() / 2.0);

		g.setColor(PICK_MARKER);
		g.fillOval(px - PICK_MARKER_RADIUS, py - PICK_MARKER_RADIUS,
				PICK_MARKER_RADIUS * 2, PICK_MARKER_RADIUS * 2);
	}

	@Override
	public void setBounds(final int x, final int y, final int width, final int height) {
		boolean resized = width != getWidth() || height != getHeight();
		super.setBounds(x, y, width, height);

		if (resized && isShowing()) {
			requestRender();
		}
	}

	/** Blocks per pixel of the image currently on screen, which may be a draft. */
	public double renderedBlocksPerPixel() {
		return currentSpan / Math.max(1, getWidth());
	}

	private static final long serialVersionUID = 1L;
}
