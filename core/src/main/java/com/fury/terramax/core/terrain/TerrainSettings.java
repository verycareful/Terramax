package com.fury.terramax.core.terrain;

/**
 * Tuning for terrain relief.
 *
 * <p>Widths are fractions of crust spacing so the whole system rescales together
 * when crust cell size changes. Heights are absolute blocks, because the
 * dimension's vertical range does not scale with anything horizontal.
 *
 * <p><b>{@code rangeWidthFraction} is still knowingly stale, and it is now the main
 * thing holding range shape back.</b> It was tuned against 100,000-block plate
 * spacing and is multiplied by 6,000-block crust spacing, so a range half-width is
 * 3,180 blocks while a collision range rises 1,400. That is a mean gradient near a
 * half, and measured flanks sustain 7.4 blocks of rise per block over hundreds of
 * blocks, which is a wall rather than a mountainside. The profiles that would make
 * a range legible, a foreland basin ahead of a collision or a coastal strip in
 * front of an arc, have no room to sit in at this width. Widening it is a tuning
 * pass with its own measurements, not a constant to change in passing.
 *
 * @param blendWidthFraction       distance over which neighbouring crust bases blend, in crust spacings
 * @param rangeWidthFraction       half-width of a mountain range, in crust spacings
 * @param continentalCollisionRise peak rise where two continental plates collide, in blocks
 * @param subductionArcRise        peak rise on the overriding plate of a subduction zone
 * @param oceanicArcRise           peak rise where two oceanic plates converge
 * @param trenchDrop               depth of the trench on the subducting side
 * @param continentalRiftDrop      depth of a rift valley where continent pulls apart
 * @param riftShoulderRise         height of the uplifted shoulders flanking that valley
 * @param faultBlockRise           height of one tilted block above the basin floor beside
 *                                 it, in blocks
 * @param faultBlockSubsidence     how far a stretched province sits below the ground
 *                                 around it, in blocks. Extension thins crust, so the
 *                                 whole province is lower, and this is what closes its
 *                                 basins rather than leaving them as open troughs
 * @param faultBlockSpacingFraction distance from one fault block to the next, in crust
 *                                 spacings. Sets how many ranges a province contains
 * @param hotspots                 relief from mantle plumes, which is the one source of
 *                                 uplift that ignores plate margins entirely
 * @param oceanicRidgeRise         height of a mid-ocean ridge where ocean floor spreads
 * @param fossilSutureRise         peak rise of a fossil suture at full survival, in
 *                                 blocks, before its hashed age decays it
 * @param transformRelief          relief at transform margins, which build very little
 * @param reliefVariationFraction  how much relief varies along a range, as a fraction of its height
 * @param detailAmplitude          small-scale roughness applied everywhere above sea level
 * @param detailWavelength         wavelength of that roughness, in blocks
 * @param regionReliefWavelengthFactor global multiplier on each region type's own
 *                                     declared wavelength, for tuning without
 *                                     editing the type table
 * @param grain                        parallel ridge structure inside a range
 */
public record TerrainSettings(
		double blendWidthFraction,
		double rangeWidthFraction,
		double continentalCollisionRise,
		double subductionArcRise,
		double oceanicArcRise,
		double trenchDrop,
		double continentalRiftDrop,
		double riftShoulderRise,
		double faultBlockRise,
		double faultBlockSubsidence,
		double faultBlockSpacingFraction,
		Hotspots hotspots,
		double oceanicRidgeRise,
		double fossilSutureRise,
		double transformRelief,
		double reliefVariationFraction,
		double detailAmplitude,
		double detailWavelength,
		double regionReliefWavelengthFactor,
		Grain grain) {

	/**
	 * Parallel ridge structure inside a mountain range.
	 *
	 * <p>A range without this is one smooth swell. Real ranges are corrugated: a line
	 * of ridges running the length of the range, separated by valleys. That structure
	 * comes from sampling noise <b>anisotropically</b> in the boundary's own
	 * {@code (across, along)} frame rather than in world coordinates. Feed the same
	 * noise a compressed across-axis and a stretched along-axis and every feature
	 * comes out many times longer than it is wide, aligned with the range.
	 *
	 * <p>The alternative, which the first build used, is isotropic noise multiplying a
	 * directional envelope. That gives a directional blob with random lumps on it, and
	 * no amount of tuning turns lumps into ridges.
	 *
	 * @param acrossWavelength spacing between parallel ridges, in blocks
	 * @param alongFactor      how many times longer a ridge is than it is wide
	 * @param depth            how far valleys cut into the range envelope, in [0, 1];
	 *                         0.5 puts valley floors at half the crest height
	 * @param octaves          detail in the ridge field
	 */
	public record Grain(double acrossWavelength, double alongFactor, double depth, int octaves) {
		public Grain {
			if (depth < 0.0 || depth > 1.0) {
				throw new IllegalArgumentException("depth must be in [0, 1], got " + depth);
			}

			if (alongFactor < 1.0) {
				throw new IllegalArgumentException(
						"alongFactor must be at least 1.0, got " + alongFactor
								+ ". Below 1 the grain runs across the range rather than along it.");
			}
		}

		public double alongWavelength() {
			return acrossWavelength * alongFactor;
		}
	}

	/**
	 * Relief from mantle plumes, which sit still while the plates slide over them.
	 *
	 * <p>Two landforms from one mechanism, chosen by the crust over the plume. On
	 * ocean floor a shield volcano grows over the plume and is carried off by the
	 * plate, so the result is a chain: an active shield at the plume and a line of
	 * older, lower, more eroded ones trailing away in the direction the plate moves,
	 * ending as seamounts. Hawaii. On a continent the plume lifts a broad swell with a
	 * caldera at its centre, and what trails behind is a plain of old flows sitting
	 * below the ground around it. Yellowstone and the Snake River Plain.
	 *
	 * <p>Lengths are in crust spacings and heights in blocks, as everywhere else here.
	 *
	 * @param spacingFraction     mean distance between plume lattice cells
	 * @param density             fraction of those cells that hold a plume, in [0, 1].
	 *                            Below one so the field is sparse and irregular rather
	 *                            than one plume per cell like clockwork
	 * @param trackFraction       length of the trail behind a plume under the fastest
	 *                            plate. A slower plate carries its volcanoes a shorter
	 *                            way in the same time, so the trail scales with speed
	 * @param shieldRise          height of the active oceanic shield above the seafloor
	 * @param shieldRadiusFraction footprint radius of that shield. Large, because a shield
	 *                            is a shield and not a cone
	 * @param chainSpacingFraction distance from one shield to the next along the chain
	 * @param domeRise            height of the continental swell over the plume
	 * @param domeRadiusFraction  radius of that swell
	 * @param calderaDrop         how far the caldera at its centre sinks below the swell
	 * @param calderaRadiusFraction radius of the caldera
	 * @param trackDrop           how far the plain of old flows sits below its
	 *                            surroundings
	 * @param trackHalfWidthFraction half-width of that plain
	 */
	public record Hotspots(
			double spacingFraction,
			double density,
			double trackFraction,
			double shieldRise,
			double shieldRadiusFraction,
			double chainSpacingFraction,
			double domeRise,
			double domeRadiusFraction,
			double calderaDrop,
			double calderaRadiusFraction,
			double trackDrop,
			double trackHalfWidthFraction) {
		public Hotspots {
			if (density < 0.0 || density > 1.0) {
				throw new IllegalArgumentException("density must be in [0, 1], got " + density);
			}

			if (chainSpacingFraction <= 0.0) {
				throw new IllegalArgumentException(
						"chainSpacingFraction must be positive, got " + chainSpacingFraction);
			}
		}

		/**
		 * How far from a plume any of its relief can reach, in crust spacings.
		 *
		 * <p>The trail under the fastest plate, plus whichever is wider of the shield
		 * at its end and the plain around it, or the swell around the plume itself.
		 * The search radius, so it has to be an overestimate rather than a guess.
		 */
		public double reachFraction() {
			return Math.max(
					domeRadiusFraction,
					trackFraction + Math.max(shieldRadiusFraction, trackHalfWidthFraction));
		}
	}

	/**
	 * Defaults sized for the y=-256 to 1792 dimension.
	 *
	 * <p>The first cross-section showed terrain using only the bottom 15% of that
	 * range, with land 0 to 112 blocks above sea level. These numbers are chosen to
	 * fill it: a continental collision rising 1,400 blocks above a base near y=112
	 * reaches roughly y=1500, which uses the height the dimension is paying for.
	 *
	 * <p>Range half-width is 0.10 of plate spacing, so 10,000 blocks at the default.
	 * That is proportionate: the Himalayas are around 250km across against plates
	 * some thousands of km wide.
	 *
	 * <p><b>Trenches are shallow, and not by choice.</b> With sea level at y=0 and
	 * the dimension floor at y=-256 there are 256 blocks of depth available,
	 * against 1,792 blocks of sky. Earth is roughly symmetric: Everest at 8.8km
	 * against the Mariana Trench at -11km. An earthlike trench here would punch
	 * through the bottom of the world. The oceanic base already sits as low as -160,
	 * so -80 is close to all the room left. Deepening trenches means raising sea
	 * level, which spends buildable height to buy ocean depth.
	 */
	public static TerrainSettings defaults() {
		return new TerrainSettings(
				1.60,
				0.53,
				1400.0,
				900.0,
				420.0,
				-45.0,
				-110.0,

				// Shoulders stand roughly twice the depth of the floor they flank. The
				// East African Rift drops about 1km below its surroundings and its
				// shoulders rise 2 to 3km above them, which is why the rift reads as a
				// mountain range with a gash in it rather than as a valley.
				240.0,

				// Basin and Range crests stand about 2,500m over the floors beside
				// them. The collision rise fixes the scale at 1,400 blocks for
				// Everest's 8,800m, so 2,500m is 400, and 420 allows for flanks that
				// are narrower here than on Earth.
				420.0,

				// Those floors sit around 600m below the Colorado Plateau next door,
				// which is 95 blocks on the same conversion. This is the number that
				// decides whether basins close, so it is measured against the endorheic
				// share rather than taken from the analogy alone.
				110.0,

				// 1,200 blocks between ranges, against 25 to 50km on Earth. Puts a
				// little over five ranges across one margin's width, and provinces tile,
				// so a belt holds many more than five.
				0.20,

				// Earth has around fifty hotspots on 510 million square kilometres, one
				// per 3,000km or so, which at 35m per block is 85,000 blocks. Twelve
				// crust spacings at six tenths density gives a mean separation near
				// that, and a plume every plate or two rather than one per plate.
				//
				// The chain is three spacings, 18,000 blocks under the fastest plate.
				// Hawaii's islands run about 600km from the Big Island to Kauai before
				// the chain goes under as atolls and seamounts, and the Snake River
				// Plain is about as long.
				//
				// A shield rises 780 over the seafloor, so with the oceanic base at -115
				// the active summit stands near y=665, Mauna Kea's 4,200m over the sea
				// at 6.3m per block. Its radius is 2,100, the Big Island being about
				// 150km across. The continental swell is a tenth of that height over
				// four times the radius: Yellowstone's is 600km wide and 500m high, and
				// the caldera in the middle of it is 60km across and a few hundred
				// metres deep.
				new Hotspots(12.0, 0.6, 3.0, 780.0, 0.35, 0.6, 110.0, 1.3, 45.0, 0.15, 40.0, 0.35),
				260.0,

				// The ceiling for the youngest seam in a belt, not the typical one. A
				// collision at 1,400 worn to a third is an Appalachian: a range, and
				// forested to the summit rather than anywhere near the treeline. Age
				// takes most seams well below this, so the measured mean is 18 blocks
				// against a tallest of 520.
				500.0,
				60.0,
				0.45,
				28.0,
				900.0,
				1.0,

				// 900-block ridge spacing at 12x elongation gives ridges roughly
				// 11,000 blocks long inside a range 6,400 wide, so a few run most of
				// its length rather than dozens of short ones. Depth 0.55 puts valley
				// floors a little over half the crest height, which is about right
				// for a young range.
				new Grain(900.0, 12.0, 0.55, 3));
	}

	public double blendWidthBlocks(final double crustSpacing) {
		return crustSpacing * blendWidthFraction;
	}

	public double rangeWidthBlocks(final double crustSpacing) {
		return crustSpacing * rangeWidthFraction;
	}

	public double faultBlockSpacingBlocks(final double crustSpacing) {
		return crustSpacing * faultBlockSpacingFraction;
	}
}
