package com.fury.terramax.core.fluvial;

/**
 * Tuning for the drainage subsystem.
 *
 * <p>Three groups, matching the three tiers, plus the carve and the query.
 *
 * <p><b>{@code provinceMarginBlocks} is the assumption the whole design rests on.</b>
 * Basins are keyed by outlet cell rather than by tile, which is what makes them
 * seamless: two tiles that both contain a basin straddling their shared edge find the
 * same outlet, so they agree by construction rather than by luck.
 *
 * <p>The condition is not quite "margin exceeds the largest basin". A basin straddling
 * a tile edge sits half in each tile, so what has to clear both extent edges is half
 * its span, plus enough room that the divides bounding it are also decided by
 * topography rather than by where the extent was cut. Requiring the margin to exceed
 * 1.5 times the largest basin gives that.
 *
 * <p>Measured, the largest basin found is 142,800 blocks across, so 214,000 would
 * satisfy the condition. 512,000 is used instead because it costs almost nothing: one
 * tile solve goes from 16,384 nodes to 36,864, about a second, amortised over the 16
 * million chunks a tile covers. This is the guarantee the whole tiering rests on and
 * it is not worth running close to the bound to save a second.
 *
 * <p><b>{@code baseLevelY} is -35, not sea level, and that is deliberate.</b> Rivers
 * grade to the low glacial stand as Earth's did, and the present sea then floods their
 * lower reaches. Three things fall out of it at once: rias, the continental shelf as
 * the drowned former coastal plain, and the possibility of fjords later without
 * revisiting a single channel elevation. Grading to y=0 instead would mean redoing
 * every channel in the world when the coast work lands.
 *
 * <p><b>{@code basinLatticeBlocks} is 1,000 and cannot be much coarser.</b> Ranges are
 * about 5,000 blocks wide and regions sit 2,300 apart, so anything coarser cannot
 * resolve a gap through a ridge or the edge of a plateau. Water gaps are a named
 * target, and a lattice that cannot see a range cannot cut through one.
 *
 * <p><b>{@code provinceLatticeBlocks} is 8,000 because tier 1 answers only one
 * question.</b> It assigns basin identity, nothing else, and which way a basin drains
 * is a question about tens of thousands of blocks. Running tier 1 at tier 2's
 * resolution over its margined extent would be 16 million nodes rather than 16
 * thousand, to learn the same answer.
 *
 * <p><b>{@code closedBasinMinDepthBlocks} is what keeps endorheic basins rare.</b> A
 * 1,000-block lattice over noisy uplift finds hundreds of shallow depressions per
 * basin, and letting every one of them close the drainage above it put 78 percent of an
 * arid basin's land beyond reach of the sea, against about 18 percent on Earth. Most of
 * those pits are a few blocks deep, and a river crossing a sill that shallow cuts
 * through it rather than ponding behind it forever. Only a genuinely deep sill survives
 * incision, and those are the basins the design is after: Caspian, Tarim, Great Basin,
 * Lake Eyre.
 *
 * <p><b>{@code bifurcationRatio} is children attempted, not the ratio that results.</b>
 * A creek stops where it meets the uplift budget, and a branch spawned further up a
 * hillside has less room left to climb, so deeper branches die more often than shallow
 * ones. Attempting Horton's 4 therefore measured 1.88 on the network that survived.
 * Attempting 8 measures 3.85, which is Horton's number where it is actually observable:
 * on the ground.
 *
 * <p><b>{@code gradientScale} depends on how much headroom a creek starts with, and
 * that changed.</b> A creek climbs at this rate and terminates where it meets the uplift
 * budget. While channel beds were read off the uplift surface a creek began level with
 * the ground, so anything steeper than the broad hillslope gradient of about 0.017
 * killed it at its first step, and 0.06 produced almost no creek length at all.
 *
 * <p>With channels incising, a creek now starts below the ground and has room to climb.
 * Re-swept on that footing, 0.06 gives 1,590 blocks of channel spacing where 0.010 gives
 * 814, far denser than intended. 0.06 is also the more realistic figure on its own
 * terms: six percent is an ordinary headwater stream gradient, and the 0.017 measured
 * earlier was the uplift surface averaged over 1,000 blocks, which is a landscape-scale
 * number rather than the slope beside a creek.
 *
 * <p><b>{@code channelGradientScale} is what makes rivers cut.</b> Channel beds are not
 * read off the uplift surface. They are integrated upstream from each outlet by adding
 * the slope-area relation at every step, and the bed is then the lower of that profile
 * and the ground. Where uplift stands far above the profile, as in a mountain, the river
 * cuts a gorge; where the ground already sits near it, as on a plain, it barely cuts at
 * all. Valley depth becomes a consequence of relief and discharge rather than a
 * constant, which is the whole claim of making rivers primary.
 *
 * <p>Reading beds off the uplift surface instead, as this first did, meant the carve
 * interpolated between uplift-derived values and could never cut below them. Measured,
 * it removed a mean of 4.7 blocks and cut more than 10 blocks on 8 percent of land: a
 * smoothing pass in a river's clothes.
 *
 * <p>The constant sets how fast the profile climbs inland, and it cuts both ways. Too
 * large and the profile meets the ground a short way upstream, so only coasts incise.
 * Too small and it stays near sea level far inland, giving canyons everywhere.
 *
 * <p>Five values here have no defensible starting guess and are found in the simulator
 * against the drainage statistics: {@code gradientScale},
 * {@code floodplainWidthFactor}, {@code hillslopeExponent},
 * {@code detailFloorFraction} and {@code evaporationFactor}. The numbers below are a
 * place to begin measuring from, not claims about what is right.
 *
 * @param provinceLatticeBlocks      tier 1 node spacing
 * @param provinceTileBlocks         tier 1 tile size, the unit that gets cached
 * @param provinceMarginBlocks       tier 1 overlap; must exceed 1.5x the largest basin
 * @param basinLatticeBlocks         tier 2 node spacing
 * @param baseLevelY                 elevation rivers grade to, the low glacial stand
 * @param channelSpacingTargetBlocks tier 2 channel density, set by quantile not constant
 * @param creekSpacingBlocks         tier 3 target spacing between headwater creeks
 * @param creekLevels                tier 3 recursion depth
 * @param bifurcationRatio           children <i>attempted</i> per tier 3 branch; see the note above
 * @param lengthRatio                how much shorter each tier 3 level is; Horton's is near 2
 * @param junctionAngleMinDegrees    narrowest tributary junction, facing upstream
 * @param junctionAngleMaxDegrees    widest tributary junction
 * @param hackExponent               exponent in Hack's law, length against area
 * @param slopeAreaExponent          exponent in the slope-area relation; makes headwaters steep
 * @param gradientScale              constant in the slope-area relation for tier 3 creeks
 * @param channelGradientScale       the same constant for tier 2 channels; sets how deeply rivers incise
 * @param floodplainWidthFactor      how far a floodplain reaches toward the divide, per unit discharge
 * @param hillslopeExponent          skew on the channel-to-divide profile; 1.0 is pure smoothstep
 * @param detailFloorFraction        detail amplitude at a channel, as a share of its amplitude at a divide
 * @param evaporationFactor          scales the vapour deficit that decides whether a lake spills
 * @param closedBasinMinDepthBlocks  sill depth below which a depression is incised through, not closed
 * @param bucketSizeBlocks           spatial index cell for the nearest-channel search
 * @param basinCacheLimit            tier 2 solves held resident; sized for wide renders, not for play
 * @param creekCacheLimit            tier 3 patches held resident
 */
public record DrainageSettings(
		double provinceLatticeBlocks,
		double provinceTileBlocks,
		double provinceMarginBlocks,
		double basinLatticeBlocks,
		double baseLevelY,
		double channelSpacingTargetBlocks,
		double creekSpacingBlocks,
		int creekLevels,
		double bifurcationRatio,
		double lengthRatio,
		double junctionAngleMinDegrees,
		double junctionAngleMaxDegrees,
		double hackExponent,
		double slopeAreaExponent,
		double gradientScale,
		double channelGradientScale,
		double floodplainWidthFactor,
		double hillslopeExponent,
		double detailFloorFraction,
		double evaporationFactor,
		double closedBasinMinDepthBlocks,
		double bucketSizeBlocks,
		int basinCacheLimit,
		int creekCacheLimit) {

	public DrainageSettings {
		if (provinceMarginBlocks < provinceTileBlocks * 0.25) {
			throw new IllegalArgumentException(
					"province margin must be a substantial fraction of the tile, or basins "
							+ "straddling a tile edge will be resolved differently by each side");
		}

		if (basinLatticeBlocks <= 0.0 || provinceLatticeBlocks <= 0.0) {
			throw new IllegalArgumentException("lattice spacings must be positive");
		}
	}

	public static DrainageSettings defaults() {
		return new DrainageSettings(
				8_000.0,      // provinceLatticeBlocks
				512_000.0,    // provinceTileBlocks
				512_000.0,    // provinceMarginBlocks
				1_000.0,      // basinLatticeBlocks
				-35.0,        // baseLevelY
				6_000.0,      // channelSpacingTargetBlocks
				2_000.0,      // creekSpacingBlocks
				3,            // creekLevels
				8.0,          // bifurcationRatio
				2.0,          // lengthRatio
				45.0,         // junctionAngleMinDegrees
				75.0,         // junctionAngleMaxDegrees
				0.57,         // hackExponent
				0.5,          // slopeAreaExponent
				0.060,        // gradientScale
				0.0005,       // channelGradientScale
				0.35,         // floodplainWidthFactor
				1.0,          // hillslopeExponent
				0.15,         // detailFloorFraction
				1.0,          // evaporationFactor
				75.0,         // closedBasinMinDepthBlocks
				2_000.0,      // bucketSizeBlocks
				1_024,        // basinCacheLimit
				4_096);       // creekCacheLimit
	}

	/**
	 * A copy with one value changed, so a sweep does not have to restate the other
	 * twenty-two. Restating them is how a swept constant quietly ends up applied to the
	 * wrong field.
	 */
	public DrainageSettings withChannelGradient(final double scale) {
		return new DrainageSettings(
				provinceLatticeBlocks, provinceTileBlocks, provinceMarginBlocks,
				basinLatticeBlocks, baseLevelY, channelSpacingTargetBlocks,
				creekSpacingBlocks, creekLevels, bifurcationRatio, lengthRatio,
				junctionAngleMinDegrees, junctionAngleMaxDegrees, hackExponent,
				slopeAreaExponent, gradientScale, scale, floodplainWidthFactor,
				hillslopeExponent, detailFloorFraction, evaporationFactor,
				closedBasinMinDepthBlocks, bucketSizeBlocks, basinCacheLimit,
				creekCacheLimit);
	}

	public DrainageSettings withCreekGradient(final double scale) {
		return new DrainageSettings(
				provinceLatticeBlocks, provinceTileBlocks, provinceMarginBlocks,
				basinLatticeBlocks, baseLevelY, channelSpacingTargetBlocks,
				creekSpacingBlocks, creekLevels, bifurcationRatio, lengthRatio,
				junctionAngleMinDegrees, junctionAngleMaxDegrees, hackExponent,
				slopeAreaExponent, scale, channelGradientScale, floodplainWidthFactor,
				hillslopeExponent, detailFloorFraction, evaporationFactor,
				closedBasinMinDepthBlocks, bucketSizeBlocks, basinCacheLimit,
				creekCacheLimit);
	}

	public DrainageSettings withClosedBasinDepth(final double blocks) {
		return new DrainageSettings(
				provinceLatticeBlocks, provinceTileBlocks, provinceMarginBlocks,
				basinLatticeBlocks, baseLevelY, channelSpacingTargetBlocks,
				creekSpacingBlocks, creekLevels, bifurcationRatio, lengthRatio,
				junctionAngleMinDegrees, junctionAngleMaxDegrees, hackExponent,
				slopeAreaExponent, gradientScale, channelGradientScale,
				floodplainWidthFactor, hillslopeExponent, detailFloorFraction,
				evaporationFactor, blocks, bucketSizeBlocks, basinCacheLimit,
				creekCacheLimit);
	}

	/** Province lattice cells across one tile plus both margins. */
	public int provinceExtentCells() {
		return (int) Math.round(
				(provinceTileBlocks + 2.0 * provinceMarginBlocks) / provinceLatticeBlocks);
	}

	/** Province lattice cells across one tile, without margin. */
	public int provinceTileCells() {
		return (int) Math.round(provinceTileBlocks / provinceLatticeBlocks);
	}

	/** Province lattice cells in one margin. */
	public int provinceMarginCells() {
		return (int) Math.round(provinceMarginBlocks / provinceLatticeBlocks);
	}
}
