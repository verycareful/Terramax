package com.fury.terramax.core.plate;

/**
 * One margin, seen from one world position.
 *
 * <p>A margin is a <b>pair of adjacent crust cells</b>, and that is the important
 * thing about this record. It is stored as the pair, canonically ordered, rather
 * than as "my cell and the one across from me". Which side the query happens to sit
 * on is then a derived question, answered by the sign of {@link #across()}.
 *
 * <p><b>Storing it the other way around put 1,300-block vertical walls through every
 * range.</b> When the pair is anchored to the querying cell, stepping across any
 * bisector replaces the entire set of margins in reach: the pair {@code (A, C)} that
 * was building a collision range simply ceases to exist and {@code (B, C)} appears in
 * its place, building something else. Nothing fades; the world changes its mind
 * between one block and the next. Anchored to the pair, a margin is the same object
 * from both sides and from every position near it, so the only thing that varies
 * across a range is the position at which its profile is read.
 *
 * <p>Carries both the plate, which supplies motion and therefore boundary behaviour,
 * and the crust cell, which supplies crust type and base elevation. They are separate
 * because a plate holds many cells of both kinds. {@code neighbourCrust} is the crust
 * across the margin rather than the neighbouring plate's type, because a plate no
 * longer has one type: a subduction zone is ocean meeting continent at a specific
 * place, not the average composition of two plates.
 *
 * @param lowPlate      plate owning the canonically first cell of the pair
 * @param highPlate     plate owning the canonically second cell
 * @param lowCrust      canonically first crust cell of the pair
 * @param highCrust     canonically second crust cell
 * @param boundaryType  what the two plates are doing to each other
 * @param across        signed distance from the margin in blocks, negative on
 *                      {@code lowCrust}'s side and positive on {@code highCrust}'s.
 *                      Signed because ranges are not symmetric: a subduction margin
 *                      has a trench on one flank and an arc on the other, and a
 *                      profile that reads only the magnitude cannot tell them apart
 * @param alongBoundary blocks along the margin, the along-range axis
 * @param convergence   closing speed along the margin normal; positive closes
 * @param shear         sliding speed along the margin
 * @param weight        how much this really is the local margin, in [0, 1], falling
 *                      to zero where a third cell takes over. See
 *                      {@link com.fury.terramax.core.plate.PlateMap#forEachBoundary}
 * @param marginId      stable hash of the pair, identical from either side, for
 *                      giving one margin a character the one next to it does not have
 */
public record PlateSample(
		Plate lowPlate,
		Plate highPlate,
		CrustCell lowCrust,
		CrustCell highCrust,
		PlateBoundaryType boundaryType,
		double across,
		double alongBoundary,
		double convergence,
		double shear,
		double weight,
		long marginId) {

	/** Distance to the margin, unsigned, as a falloff wants it. */
	public double boundaryDistance() {
		return Math.abs(across);
	}

	/** The plate the query is standing on. */
	public Plate plate() {
		return across >= 0.0 ? highPlate : lowPlate;
	}

	/** The plate across the margin from the query. */
	public Plate neighbour() {
		return across >= 0.0 ? lowPlate : highPlate;
	}

	/** The crust the query is standing on. */
	public CrustCell crust() {
		return across >= 0.0 ? highCrust : lowCrust;
	}

	/** The crust across the margin from the query. */
	public CrustCell neighbourCrust() {
		return across >= 0.0 ? lowCrust : highCrust;
	}

	/** True where oceanic crust is being driven under continental. */
	public boolean isSubducting() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& lowCrust.isContinental() != highCrust.isContinental();
	}

	/** True where two continental masses collide, which builds the tallest ranges. */
	public boolean isContinentalCollision() {
		return boundaryType == PlateBoundaryType.CONVERGENT
				&& lowCrust.isContinental() && highCrust.isContinental();
	}

	/**
	 * True on the upper plate of a subduction zone, which gains an arc rather than a
	 * trench.
	 *
	 * <p><b>Not for choosing what to build.</b> This flips sign at the margin itself,
	 * so a profile that switches on it is discontinuous there by construction: the
	 * two sides evaluate different functions at the same point and disagree by
	 * whatever an arc and a trench differ by. Relief asks
	 * {@link #oceanwardSign()} instead, which is a property of the pair and does not
	 * flip. This remains for consumers that genuinely want to know which side of a
	 * margin they are standing on, such as a biome or a render.
	 */
	public boolean isOverridingPlate() {
		return isSubducting() && crust().isContinental();
	}

	/**
	 * The factor that turns {@link #across()} into a landward coordinate.
	 *
	 * <p>The orientation a one-sided range needs, expressed as a property of the two
	 * cells rather than of the query. Multiply {@code across} by this and the result
	 * runs negative out to sea and positive inland, everywhere along the margin and
	 * from either side of it, so one continuous function of that value can describe
	 * the trench, the coast, the wall and the far flank in a single expression.
	 *
	 * <p>Returns 0 where the pair does not straddle a coast and there is therefore no
	 * landward direction to name.
	 */
	public int landwardSign() {
		if (lowCrust.isContinental() == highCrust.isContinental()) {
			return 0;
		}

		// across runs positive toward highCrust, so land lies the negative way when
		// the continental half of the pair is the low one.
		return lowCrust.isContinental() ? -1 : 1;
	}
}
