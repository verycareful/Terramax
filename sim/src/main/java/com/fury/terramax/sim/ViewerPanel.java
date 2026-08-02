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
import java.util.function.Supplier;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.fury.terramax.core.plate.PlateMap;

/**
 * Pannable, zoomable view of the plate system.
 *
 * <p>Rendering happens off the event thread. A full-resolution render of a
 * continental view costs most of a second, mostly in domain warping and the
 * continent field, so doing it on the event thread would freeze the window on
 * every drag.
 *
 * <p>While the mouse is down the panel renders at reduced resolution and scales
 * the result up. Panning stays responsive and the image sharpens on release. A
 * blurry image that tracks the mouse is far more useful than a crisp one that
 * arrives a second after you stopped moving.
 */
public final class ViewerPanel extends JPanel {
	/** Pixels rendered per axis while dragging. Upscaled to fill the panel. */
	private static final int DRAFT_PIXELS = 220;

	private static final double ZOOM_STEP = 1.25;
	private static final double MIN_SPAN_BLOCKS = 512.0;
	private static final double MAX_SPAN_BLOCKS = 1.0e8;

	private static final Color BACKGROUND = new Color(18, 20, 24);
	private static final Color HUD_TEXT = new Color(226, 230, 238);
	private static final Color HUD_SHADOW = new Color(0, 0, 0, 190);

	private final transient Supplier<PlateMap> plateSource;

	private transient MapRenderer.Layer layer = MapRenderer.Layer.PLATE_TYPE;
	private double centreX;
	private double centreZ;
	private double spanBlocks;

	private transient BufferedImage current;
	private transient double currentSpan;
	private final transient AtomicBoolean rendering = new AtomicBoolean(false);
	private transient boolean pendingRender;
	private transient boolean draft;
	private transient long lastRenderMs;

	private int dragOriginX;
	private int dragOriginY;

	public ViewerPanel(final Supplier<PlateMap> plateSource, final double initialSpanBlocks) {
		this.plateSource = plateSource;
		this.spanBlocks = initialSpanBlocks;

		setBackground(BACKGROUND);
		installMouseHandlers();
	}

	public void setLayer(final MapRenderer.Layer newLayer) {
		this.layer = newLayer;
		requestRender(false);
	}

	public MapRenderer.Layer layer() {
		return layer;
	}

	/** Call after any settings change, so the view picks up the new plate map. */
	public void refresh() {
		requestRender(false);
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
				double blocksPerPixel = spanBlocks / Math.max(1, getWidth());

				centreX -= (e.getX() - dragOriginX) * blocksPerPixel;
				centreZ -= (e.getY() - dragOriginY) * blocksPerPixel;

				dragOriginX = e.getX();
				dragOriginY = e.getY();

				requestRender(true);
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				requestRender(false);
			}

			@Override
			public void mouseWheelMoved(final MouseWheelEvent e) {
				// Zoom about the cursor rather than the centre, so the point under the
				// pointer stays put. Zooming about the centre makes it impossible to
				// close in on anything off-centre.
				double blocksPerPixel = spanBlocks / Math.max(1, getWidth());
				double anchorX = centreX + (e.getX() - getWidth() / 2.0) * blocksPerPixel;
				double anchorZ = centreZ + (e.getY() - getHeight() / 2.0) * blocksPerPixel;

				double factor = e.getWheelRotation() < 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP;
				double newSpan = Math.max(MIN_SPAN_BLOCKS, Math.min(MAX_SPAN_BLOCKS, spanBlocks * factor));
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

	/**
	 * Renders in the background, coalescing requests.
	 *
	 * <p>Only one render runs at a time. Requests arriving during a render set a
	 * flag rather than queueing, so a fast drag produces one follow-up render
	 * instead of a backlog of stale frames.
	 */
	private void requestRender(final boolean draftQuality) {
		this.draft = draftQuality;

		if (!rendering.compareAndSet(false, true)) {
			pendingRender = true;
			return;
		}

		int pixels = draftQuality ? DRAFT_PIXELS : Math.max(1, Math.min(getWidth(), getHeight()));
		MapView view = new MapView(centreX, centreZ, spanBlocks, pixels);
		PlateMap plates = plateSource.get();
		MapRenderer.Layer requestedLayer = layer;

		Thread worker = new Thread(() -> {
			long start = System.nanoTime();
			BufferedImage image = MapRenderer.render(plates, view, requestedLayer);
			long elapsed = (System.nanoTime() - start) / 1_000_000L;

			SwingUtilities.invokeLater(() -> {
				current = image;
				currentSpan = view.spanBlocks();
				lastRenderMs = elapsed;
				rendering.set(false);
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

	@Override
	protected void paintComponent(final Graphics graphics) {
		super.paintComponent(graphics);

		Graphics2D g = (Graphics2D) graphics;

		if (current == null) {
			requestRender(false);
			g.setColor(HUD_TEXT);
			g.drawString("rendering...", 16, 26);
			return;
		}

		// Nearest neighbour: a draft frame upscaled smoothly looks like a
		// finished render, which hides that you are looking at a preview.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(current, 0, 0, getWidth(), getHeight(), null);

		drawHud(g);
	}

	private void drawHud(final Graphics2D g) {
		String[] lines = {
			layer.name(),
			String.format("centre  %,.0f, %,.0f", centreX, centreZ),
			String.format("span    %,.0f blocks", currentSpan),
			String.format("scale   %,.0f blocks/pixel", currentSpan / Math.max(1, getWidth())),
			String.format("render  %d ms%s", lastRenderMs, current.getWidth() <= DRAFT_PIXELS ? " (draft)" : "")
		};

		g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		int lineHeight = 16;
		int boxHeight = lines.length * lineHeight + 12;

		g.setColor(HUD_SHADOW);
		g.fillRect(8, 8, 260, boxHeight);

		g.setColor(HUD_TEXT);

		for (int i = 0; i < lines.length; i++) {
			g.drawString(lines[i], 18, 26 + i * lineHeight);
		}
	}

	@Override
	public void setBounds(final int x, final int y, final int width, final int height) {
		boolean resized = width != getWidth() || height != getHeight();
		super.setBounds(x, y, width, height);

		if (resized && isShowing()) {
			requestRender(false);
		}
	}

	private static final long serialVersionUID = 1L;
}
