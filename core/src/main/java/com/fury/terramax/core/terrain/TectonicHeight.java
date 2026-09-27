package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.plate.PlateSample;

/**
 * The surface as tectonics alone would leave it: crust base plus boundary relief.
 *
 * <p><b>This exists to break a cycle, and the cycle is the whole reason it is a
 * separate class rather than a method.</b> Regions are gated by climate, because
 * whether a landform survives as a mesa or is dissected into hills is decided by how
 * much rain falls on it. Climate is carried by wind and moisture, both of which read
 * terrain. If the terrain they read included regions, regions would depend on climate
 * and climate on regions, and neither could be computed first.
 *
 * <p>Splitting here resolves it, and resolves it in the physically correct place. The
 * causal order on a real planet is tectonics, then topography, then climate, then
 * erosion acting on that topography. Regions are an erosional outcome, so they belong
 * downstream of climate, and climate belongs downstream of the tectonic relief only.
 *
 * <p>It is also the right answer on the merits, independent of the cycle. A 45-block
 * rolling hill field must not deflect a continental airstream; a 1,400-block collision
 * range must. Wind was already tuned to ignore region relief, by taking its gradient
 * over 3,000 blocks so that anything varying over 2,300 largely cancels. This makes
 * that explicit in the types instead of leaving it to a constant that a later change
 * could quietly invalidate.
 *
 * <p>Pure function of position, as {@link HeightField} requires.
 */
public final class TectonicHeight implements HeightField {
	private final PlateMap plates;
	private final MountainRidge ridge;
	private final HotspotField hotspots;
	private final double blendWidthBlocks;

	public TectonicHeight(
			final long seed, final PlateMap plates, final TerrainSettings settings) {
		this.plates = plates;
		this.ridge = new MountainRidge(seed, settings, plates.settings().crustSpacingBlocks());
		this.hotspots = new HotspotField(seed, plates, settings.hotspots());
		this.blendWidthBlocks = settings.blendWidthBlocks(plates.settings().crustSpacingBlocks());
	}

	/**
	 * Everything one plate lookup yields, so callers need not repeat it.
	 *
	 * <p>The plate search is the expensive part of a column, and asking separately for
	 * the nearest boundary and for the relief ran it twice.
	 *
	 * @param plate       the nearest boundary and both sides of it
	 * @param type        the landform that boundary builds. Not always derivable from
	 *                    {@code plate}: whether a rift is one torn valley or a province
	 *                    of fault blocks depends on a region field that only
	 *                    {@code MountainRidge} holds, so the answer is carried here
	 *                    rather than left for callers to reconstruct
	 * @param base        crust base elevation, blended smoothly across every cell seam
	 *                    by {@code PlateMap}. Blending it here against the nearest
	 *                    differing <i>plate</i> left a step at every seam inside a
	 *                    plate, which is most of them
	 * @param relief      every source of boundary relief and plume relief, summed:
	 *                    mountains, arcs, trenches, rifts and shield volcanoes
	 * @param hotspot     the part of {@code relief} that came from plumes, which is
	 *                    the only part no margin accounts for
	 * @param interiority 0 at a plate boundary, 1 once past the blend width
	 */
	public record Sample(
			PlateSample plate, RangeType type,
			double base, double relief, double hotspot, double interiority) {
		/** The tectonic surface here. */
		public double height() {
			return base + relief;
		}
	}

	public PlateMap plates() {
		return plates;
	}

	/**
	 * The relief generator, for probes that walk margins one at a time.
	 *
	 * <p>Exposed because a margin's landform is no longer a pure function of the pair.
	 * Anything enumerating boundaries and naming them has to ask the same object the
	 * generator asks, or it reports a different world than the one being built.
	 */
	public MountainRidge ridge() {
		return ridge;
	}

	/** The plume field, for probes that enumerate hotspots rather than sample ground. */
	public HotspotField hotspots() {
		return hotspots;
	}

	public double seaLevel() {
		return plates.settings().seaLevel();
	}

	/**
	 * The tectonic surface here, and what built it.
	 *
	 * <p><b>Plume relief is added to margin relief, not chosen between.</b> The two are
	 * independent processes and a real plume under a real margin builds on top of it:
	 * Iceland is a hotspot sitting on a spreading ridge and stands higher than either
	 * would alone. Summing is also the only form that stays continuous, since both
	 * terms are continuous everywhere and a choice between them would step wherever
	 * the winner changed.
	 *
	 * <p>The <b>type</b> is a choice, and it is made here because this is the only place
	 * that sees both. It names whichever process put more relief under the column,
	 * which is the question a biome is asking: a shield volcano on the flank of an
	 * island arc is a shield volcano. The type can therefore change from one column to
	 * the next without the surface changing at all, which is correct, and is why the
	 * amount is carried separately for anything that needs to weigh rather than name.
	 */
	public Sample sample(final double worldX, final double worldZ) {
		MountainRidge.Result ridged = ridge.evaluate(plates, worldX, worldZ);
		PlateSample plate = ridged.nearest();

		double interiority = smoothstep(plate.boundaryDistance() / blendWidthBlocks);
		double plume = hotspots.reliefAt(worldX, worldZ);

		RangeType type = Math.abs(plume) > Math.abs(ridged.relief())
				? RangeType.HOTSPOT
				: ridged.type();

		return new Sample(
				plate, type, ridged.base(), ridged.relief() + plume, plume, interiority);
	}

	@Override
	public double heightAt(final double worldX, final double worldZ) {
		return sample(worldX, worldZ).height();
	}

	static double smoothstep(final double x) {
		double t = Math.max(0.0, Math.min(1.0, x));

		return t * t * (3.0 - 2.0 * t);
	}
}
