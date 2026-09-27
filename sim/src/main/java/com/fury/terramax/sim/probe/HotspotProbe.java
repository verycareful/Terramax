package com.fury.terramax.sim.probe;

import java.util.ArrayList;
import java.util.List;

import com.fury.terramax.core.terrain.HotspotField;
import com.fury.terramax.core.terrain.TectonicHeight;
import com.fury.terramax.sim.MapView;
import com.fury.terramax.sim.TerrainModel;

/**
 * Every plume in the view, and what each one actually built.
 *
 * <p><b>Enumerates features rather than sampling ground, which is why it exists
 * alongside the census.</b> A grid sweep answers how much of the world is hotspot and
 * how high it stands; it cannot answer whether any single chain came out as a chain.
 * Hotspots are the sparsest thing in the world, a few per continental view, so a sweep
 * that reports two tenths of a percent is consistent both with working chains and with
 * a field of lone bumps. This walks the plumes themselves and measures the surface at
 * each summit, so the two forms of failure are distinguishable: a chain with one
 * volcano in it, and a chain whose volcanoes are all the same height.
 *
 * <p>Heights are read from the tectonic surface at the summit rather than from the
 * shield's own rise, so what is reported is what the world has, including the crust
 * base under it and anything a margin nearby contributes.
 */
public final class HotspotProbe implements Probe {
	/** Plumes listed in full. Beyond this only the totals are reported. */
	private static final int REPORT_LIMIT = 8;

	/** Shields listed per chain. Enough to see the age progression turn into seamounts. */
	private static final int CHAIN_LIMIT = 6;

	@Override
	public String name() {
		return "hotspots";
	}

	@Override
	public String description() {
		return "every plume in the view, its chain or swell, and the height of each summit";
	}

	@Override
	public ProbeResult run(final TerrainModel model, final ProbeContext context) {
		TectonicHeight tectonic = model.snapshot().uplift().tectonic();
		HotspotField field = tectonic.hotspots();

		double spacing = model.plateSettings().crustSpacingBlocks();
		var settings = model.terrainSettings().hotspots();
		double calderaRadius = spacing * settings.calderaRadiusFraction();
		double domeRadius = spacing * settings.domeRadiusFraction();

		MapView view = context.view();
		double half = view.spanBlocks() * 0.5;

		List<HotspotField.Hotspot> plumes = new ArrayList<>();

		field.forEachHotspot(
				view.centreX() - half, view.centreZ() - half,
				view.centreX() + half, view.centreZ() + half,
				plume -> {
					if (plume.present()) {
						plumes.add(plume);
					}
				});

		ProbeResult.Builder out = ProbeResult.titled(String.format(
				"  %d plumes over %,.0f blocks, lattice spacing %,.0f",
				plumes.size(), view.spanBlocks(), field.lattice().spacing()));

		if (plumes.isEmpty()) {
			out.row("    none. The lattice is sparse by design; widen the view.");
			out.blank();

			return out.build();
		}

		int oceanic = 0;
		int summits = 0;
		int islands = 0;
		double tallest = Double.NEGATIVE_INFINITY;
		int chains = 0;

		for (HotspotField.Hotspot plume : plumes) {
			if (!plume.continental()) {
				oceanic++;
			}
		}

		out.row("    %d oceanic chains, %d continental swells",
				oceanic, plumes.size() - oceanic);
		out.blank();

		int listed = 0;

		for (HotspotField.Hotspot plume : plumes) {
			double plumeY = tectonic.sample(plume.x(), plume.z()).height();
			boolean verbose = listed < REPORT_LIMIT;

			if (verbose) {
				out.row("    %-11s %,10.0f %,10.0f   y %5.0f   trail %,6.0f blocks toward %5.0f deg",
						plume.continental() ? "swell" : "chain",
						plume.x(), plume.z(), plumeY, plume.trackBlocks(),
						Math.toDegrees(Math.atan2(plume.directionZ(), plume.directionX())));

				out.place(new Place(
						String.format("%s at y %.0f", plume.continental() ? "swell" : "chain", plumeY),
						plume.x(), plume.z(),

						// Wide enough to hold the whole trail and the far shield's
						// flanks. A view framed on the plume alone shows the active
						// volcano and nothing of the thing that makes it a chain.
						plume.trackBlocks() * 2.5));
			}

			if (plume.continental()) {
				if (verbose) {
					// The plume's own contribution, not the height. A swell is 110
					// blocks on ground that a nearby margin can put at y=674, so a
					// height here measures the margin and says nothing about the swell.
					// This is the reason the sample carries the two apart.
					out.row("      swell relief: caldera %5.0f, rim %5.0f, edge %5.0f, track %5.0f",
							plumeRelief(tectonic, plume, 0.0),
							plumeRelief(tectonic, plume, calderaRadius),
							plumeRelief(tectonic, plume, domeRadius * 0.55),
							trackRelief(tectonic, plume));
				}

				listed++;
				continue;
			}

			StringBuilder chain = new StringBuilder();

			// shields, shields above water, tallest summit
			double[] counted = {0.0, 0.0, Double.NEGATIVE_INFINITY};

			field.forEachVolcano(plume, (x, z, age, radius, rise) -> {
				double summitY = tectonic.sample(x, z).height();

				counted[0]++;
				counted[2] = Math.max(counted[2], summitY);

				if (summitY > 0.0) {
					counted[1]++;
				}

				if (counted[0] <= CHAIN_LIMIT) {
					chain.append(String.format("%6.0f", summitY));
				}
			});

			summits += (int) counted[0];
			islands += (int) counted[1];
			tallest = Math.max(tallest, counted[2]);
			chains++;

			if (verbose) {
				out.row("      %d shields, %d above water, summits y%s",
						(int) counted[0], (int) counted[1], chain);
			}

			listed++;
		}

		if (plumes.size() > REPORT_LIMIT) {
			out.blank();
			out.row("    %d further plumes not listed", plumes.size() - REPORT_LIMIT);
		}

		if (summits > 0) {
			out.blank();
			out.row("    %d shields over %d chains, %d of them islands (%.0f%%), tallest y %.0f",
					summits, chains, islands, 100.0 * islands / summits, tallest);
		}

		out.blank();

		return out.build();
	}

	/**
	 * The plume's own relief at a distance from it, measured across the swell rather
	 * than along the track.
	 *
	 * <p>Sampled perpendicular to the plate's motion so the track, which runs the other
	 * way, cannot contribute. Otherwise a rim reading a hundred blocks low would be the
	 * plain of old flows rather than the caldera, and the two would be indistinguishable
	 * from one number.
	 */
	private static double plumeRelief(
			final TectonicHeight tectonic, final HotspotField.Hotspot plume, final double distance) {
		return tectonic.sample(
				plume.x() - plume.directionZ() * distance,
				plume.z() + plume.directionX() * distance).hotspot();
	}

	/** The plume's relief halfway along its track, which is the plain of old flows. */
	private static double trackRelief(
			final TectonicHeight tectonic, final HotspotField.Hotspot plume) {
		return tectonic.sample(
				plume.x() + plume.directionX() * plume.trackBlocks() * 0.5,
				plume.z() + plume.directionZ() * plume.trackBlocks() * 0.5).hotspot();
	}
}
