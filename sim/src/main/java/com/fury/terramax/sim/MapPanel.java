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

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The map itself: pan, zoom, render, and whatever the current mode does with a
 * click.
 *
 * <p>Rendering happens off the event thread through {@link TileRenderer}, with tiles
 * painted as they land. While the mouse is down the panel renders at reduced
 * resolution and scales up, because a blurry image that tracks the mouse is far more
 * useful than a crisp one arriving a second after you stopped moving.
 *
 * <p>Knows nothing about controls, statistics or the section plot. It reports what
 * happened through {@link MapListener} and lets the frame decide what to do about it.
 */
public final class MapPanel extends JPanel {
	/** Pixels rendered per axis while dragging. Upscaled to fill the panel. */
	private static final int DRAFT_PIXELS = 320;

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

	/** The dimension's vertical range and sea level. */
	public static final int MIN_Y = -256;
	public static final int MAX_Y = 1792;
	public static final int SEA_LEVEL = 0;

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

		void renderComplete(MapView view, long elapsedMs);
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
	private final transient AtomicBoolean rendering = new AtomicBoolean(false);
	private transient boolean pendingRender;
	private transient boolean draft;

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
		requestRender(false);
	}

	/** Selects a terrain layer, which takes precedence over the plate layer. */
	public void setTerrainLayer(final MapRenderer.TerrainLayer layer) {
		this.terrainLayer = layer;
		requestRender(false);
	}

	public MapRenderer.TerrainLayer terrainLayer() {
		return terrainLayer;
	}

	public MapRenderer.Layer plateLayer() {
		return plateLayer;
	}

	/** Call after any settings change, so the view picks up the rebuilt world. */
	public void refresh() {
		requestRender(false);
	}

	/** Centres the view on a coordinate without changing the zoom. */
	public void goTo(final double worldX, final double worldZ) {
		this.centreX = worldX;
		this.centreZ = worldZ;
		requestRender(false);
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

				requestRender(true);
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				requestRender(false);
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

				requestRender(true);
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
	 * Renders in the background, coalescing requests.
	 *
	 * <p>Only one render runs at a time. Requests arriving during a render set a flag
	 * rather than queueing, so a fast drag produces one follow-up render instead of a
	 * backlog of stale frames.
	 */
	private void requestRender(final boolean draftQuality) {
		this.draft = draftQuality;

		if (!rendering.compareAndSet(false, true)) {
			pendingRender = true;
			return;
		}

		int pixels = draftQuality
				? DRAFT_PIXELS
				: Math.max(1, Math.min(getWidth(), getHeight()));

		MapView view = new MapView(centreX, centreZ, spanBlocks, pixels);

		// One snapshot for the whole render. Taking the three maps separately would
		// let a settings change land between them and leave the plate map and the
		// terrain describing different worlds.
		TerrainModel.Snapshot world = model.snapshot();

		MapRenderer.Layer requestedPlate = plateLayer;
		MapRenderer.TerrainLayer requestedTerrain = terrainLayer;

		Thread worker = new Thread(() -> {
			long start = System.nanoTime();

			BufferedImage image = requestedTerrain != null
					? MapRenderer.renderTerrainProgressive(
							world.terrain(), world.plates(), world.regions(), view, requestedTerrain,
							MIN_Y, MAX_Y, SEA_LEVEL, partial -> showPartial(partial, view))
					: MapRenderer.renderProgressive(
							world.plates(), view, requestedPlate, partial -> showPartial(partial, view));

			long elapsed = (System.nanoTime() - start) / 1_000_000L;

			SwingUtilities.invokeLater(() -> {
				current = image;
				currentSpan = view.spanBlocks();
				rendering.set(false);
				listener.renderComplete(view, elapsed);
				repaint();

				if (pendingRender) {
					pendingRender = false;
					requestRender(draft);
				}
			});
		}, "terramax-render");

		worker.setDaemon(true);
		worker.start();
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
			requestRender(false);
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
			requestRender(false);
		}
	}

	/** Blocks per pixel of the image currently on screen, which may be a draft. */
	public double renderedBlocksPerPixel() {
		return currentSpan / Math.max(1, getWidth());
	}

	private static final long serialVersionUID = 1L;
}
