package com.fury.terramax.sim;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.fury.terramax.core.plate.CrustType;
import com.fury.terramax.core.plate.PlateBoundaryType;

/**
 * The right column: what the visible area actually measures.
 *
 * <p>These numbers previously printed to the console on the batch run only, which is
 * exactly backwards. The batch run is where you already have the images; the
 * interactive tool is where you are moving a slider and need to know whether the
 * number went the right way. Three bugs in this project were invisible on the maps
 * and obvious in the statistics.
 *
 * <p>Measured off the event thread after each render completes, on a coarse grid, so
 * it never delays a frame.
 */
public final class StatisticsPanel extends JPanel {
	private static final int COLUMN_WIDTH = 250;

	private final JLabel body = new JLabel();

	private transient Thread pending;

	public StatisticsPanel() {
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		setPreferredSize(new Dimension(COLUMN_WIDTH, 0));

		body.setVerticalAlignment(JLabel.TOP);
		body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		body.setText("<html>measuring...</html>");

		add(body, BorderLayout.CENTER);
	}

	/**
	 * Recomputes for the given view, in the background.
	 *
	 * <p>A render in flight supersedes any measurement still running for an older
	 * view, since a stale number beside a fresh map is worse than no number.
	 */
	public void update(final TerrainModel.Snapshot world, final MapView view) {
		if (pending != null) {
			pending.interrupt();
		}

		pending = new Thread(() -> {
			TerrainStatistics stats = TerrainStatistics.measure(
					world, view, MapPanel.SEA_LEVEL, TerrainStatistics.LIVE_GRID);

			if (!Thread.currentThread().isInterrupted()) {
				SwingUtilities.invokeLater(() -> body.setText(format(stats)));
			}
		}, "terramax-stats");

		pending.setDaemon(true);
		pending.start();
	}

	private static String format(final TerrainStatistics s) {
		StringBuilder out = new StringBuilder("<html><b>PLATES</b><br>");

		out.append(String.format("in view    %d<br>", s.plateCount()));
		out.append(String.format("smallest   %,.0f%s<br>",
				s.smallestPlateWidth(), s.plateSizeIsFloored() ? "*" : ""));
		out.append(String.format("median     %,.0f<br>", s.medianPlateWidth()));
		out.append(String.format("largest    %,.0f<br>", s.largestPlateWidth()));
		out.append(String.format("ratio      %.1fx%s<br>",
				s.plateSizeRatio(), s.plateSizeIsFloored() ? "*" : ""));

		if (s.plateSizeIsFloored()) {
			out.append("<i>* at grid limit,<br>zoom in for a real<br>figure</i><br>");
		}

		out.append("<br>");

		out.append("<b>CRUST</b><br>");
		out.append(String.format("continent  %.1f%%<br>", s.crustShare(CrustType.CONTINENTAL) * 100));
		out.append(String.format("ocean      %.1f%%<br><br>", s.crustShare(CrustType.OCEANIC) * 100));

		out.append("<b>BOUNDARIES</b><br>");
		out.append(String.format("interior   %.1f%%<br>", s.interiorShare() * 100));
		out.append(String.format("convergent %.1f%%<br>",
				s.boundaryShare(PlateBoundaryType.CONVERGENT) * 100));
		out.append(String.format("divergent  %.1f%%<br>",
				s.boundaryShare(PlateBoundaryType.DIVERGENT) * 100));
		out.append(String.format("transform  %.1f%%<br>",
				s.boundaryShare(PlateBoundaryType.TRANSFORM) * 100));
		out.append("<i>Earth: 35/50/15</i><br><br>");

		out.append("<b>ELEVATION</b><br>");
		out.append(String.format("range      %,.0f<br>", s.minHeight()));
		out.append(String.format("        to %,.0f<br>", s.maxHeight()));
		out.append(String.format("mean       %,.0f<br>", s.meanHeight()));
		out.append(String.format("above sea  %.1f%%<br>", s.aboveSeaShare() * 100));
		out.append(String.format("uses       %.0f%% of dim<br>",
				s.dimensionUsage(MapPanel.MIN_Y, MapPanel.MAX_Y) * 100));

		// Flag the two hard failures rather than leaving them to be spotted in a
		// number. Terrain outside the dimension is clipped in game, silently.
		if (s.minHeight() < MapPanel.MIN_Y || s.maxHeight() > MapPanel.MAX_Y) {
			out.append("<br><font color='#ff6b6b'><b>OUT OF BOUNDS</b></font><br>");
		}

		return out.append("</html>").toString();
	}

	private static final long serialVersionUID = 1L;
}
