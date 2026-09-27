package com.fury.terramax.core.terrain;

import com.fury.terramax.core.plate.CrustCell;
import com.fury.terramax.core.plate.Plate;
import com.fury.terramax.core.plate.PlateMap;
import com.fury.terramax.core.util.FractalNoise2D;
import com.fury.terramax.core.util.Hashing;
import com.fury.terramax.core.util.PoissonDisk;
import com.fury.terramax.core.util.VoronoiSample;

/**
 * Relief from mantle plumes: the one source of uplift that owes nothing to a margin.
 *
 * <p>Every other range in the world sits on a seam between two crust cells, so every
 * other range runs along the crust lattice, and the middle of a large plate is flat
 * for tens of thousands of blocks in every direction. A plume is a point in the
 * mantle, fixed while the plate above it slides past, and it puts relief where nothing
 * else can. That is the entire reason to have it.
 *
 * <p><b>A plume is a point, and everything about it is a property of that point.</b>
 * Which plate slides over it, and therefore which way its trail runs, is read once at
 * the plume's own position. So is the crust type, which decides whether it builds an
 * island chain or a continental swell. Neither is read at the column asking. Reading
 * either there would let one plume be oceanic on one side of a coastline and
 * continental on the other, with a step in the ground where the answer changed, which
 * is the same defect that once put walls through every range and the same fix: decide
 * per feature, not per query.
 *
 * <p>The trail runs in the direction the plate moves. A volcano built over the plume
 * is carried away with the plate, so the older a volcano the further along the motion
 * vector it sits, and a faster plate spreads the same ages over a longer line. That
 * age progression comes free from {@link Plate#motionX()}, which the plate map already
 * hashes for the sake of boundary classification.
 *
 * <p>Pure function of position. The plate lookup a plume needs is cached per thread,
 * because columns arrive in spatial order and the same plume answers thousands of
 * them in a row.
 */
public final class HotspotField {
	/** Keeps the plume lattice's jitter independent of every other lattice. */
	private static final long SALT_LATTICE = 0x452821E638D01377L;

	/** Decorrelates the draws made per plume. */
	private static final long SALT_PRESENCE = 21L;
	private static final long SALT_STRENGTH = 22L;

	/** Keeps the shape field independent of every other noise field in the world. */
	private static final long SALT_SHAPE = 0x452821E6BF012F37L;

	/** And the groove field independent of the shape field. */
	private static final long SALT_GROOVE = 0xC0AC29B7C97C50DDL;

	/** Decorrelates the draws made per volcano along a chain. */
	private static final long SALT_ALONG = 31L;
	private static final long SALT_ACROSS = 32L;
	private static final long SALT_SIZE = 33L;

	/**
	 * Plume offset from its lattice cell centre, as a fraction of spacing.
	 *
	 * <p>Near the maximum, because the lattice must not show. Two plumes are the only
	 * two features of their kind for a hundred thousand blocks, and if their spacing
	 * is regular there is nothing else nearby to hide it behind.
	 */
	private static final double JITTER = 0.45;

	/** Slots in the per-thread cache of resolved plumes. A power of two. */
	private static final int CACHE_SLOTS = 64;

	/**
	 * Weakest plume as a fraction of the strongest.
	 *
	 * <p>Not lower, because a plume too weak to breach the sea builds a seamount chain
	 * nobody will ever see, and this field exists to be seen.
	 */
	private static final double MIN_STRENGTH = 0.6;

	/**
	 * How steeply a shield loses height with age, as an exponent on {@code 1 - age}.
	 *
	 * <p>Above one so the chain sinks quickly at first and then lingers: the active
	 * shield towers over its neighbour, and the far end is a long run of seamounts
	 * rather than a single one. At the default rise the summits come out near 665,
	 * 410, 200 and 35 for the first four, so the fourth island is barely one, and the
	 * fifth is under water. That is roughly the Hawaiian sequence from the Big Island
	 * to Kauai and then Nihoa.
	 */
	private static final double AGE_FALLOFF = 1.8;

	/**
	 * How much narrower the oldest shield is than the active one.
	 *
	 * <p>Erosion takes a shield's flanks as well as its summit. Kept modest, since a
	 * seamount is still a large thing; what it has lost is height.
	 */
	private static final double SHIELD_SHRINK = 0.45;

	/** How far each shield wanders from its nominal place along the chain, in spacings. */
	private static final double CHAIN_ALONG_JITTER = 0.35;

	/**
	 * How far each shield wanders off the chain's axis, either way, in spacings.
	 *
	 * <p>Without it the chain is a perfectly straight row, which is the one thing that
	 * would give the construction away. Real chains have two or three parallel lines
	 * of vents, the loa and kea trends on Hawaii, and this is a cheap way to the same
	 * look.
	 */
	private static final double CHAIN_ACROSS_JITTER = 0.3;

	/** How much a shield's height may fall short of its age's ceiling. */
	private static final double SIZE_JITTER = 0.2;

	/**
	 * How far a shield's outline departs from a circle, as a fraction of its radius.
	 *
	 * <p><b>Not cosmetic, and the render is what proved it.</b> A dome of a plain radius
	 * draws a perfect circle, and a perfect circle was the only one in the world: every
	 * other landform gets its outline from a margin that wanders or from the grain
	 * carved into it, so two round islands in an ocean of ragged ones read as placed
	 * rather than grown. At a third of the radius the outline lobes the way a real
	 * shield does, since a shield grows along its rift zones rather than evenly, and
	 * the coastline that falls out of it is ragged because the coastline is just where
	 * this surface crosses sea level.
	 */
	private static final double SHAPE_DEPTH = 0.33;

	/**
	 * Wavelength of that field, as a fraction of a shield's radius.
	 *
	 * <p>Close to the radius itself, so the departure is a few lobes rather than a
	 * crinkled edge. Detail finer than this belongs to the erosion pass, which works on
	 * whatever shape it is given.
	 */
	private static final double SHAPE_WAVELENGTH = 0.8;

	/** Octaves in it. Three, so the lobes carry smaller irregularities of their own. */
	private static final int SHAPE_OCTAVES = 3;

	/**
	 * How deeply radial valleys cut into a shield, as a fraction of its height at the
	 * rim.
	 *
	 * <p><b>A shield without these is the only featureless surface in the world, and
	 * the render is what showed it.</b> Every margin range is corrugated by
	 * {@code MountainRidge}'s grain; a dome got nothing, so an island came out as a
	 * smooth ramp from coast to summit with a colour gradient and no landform in it.
	 * Worse, a surface that smooth has no flow direction of its own, and the drainage
	 * lattice left a regular chevron pattern across the summit where it had nothing to
	 * key on.
	 *
	 * <p>Radial is the one correct direction. Water leaving a cone runs straight down
	 * it, so the valleys it cuts are radial by construction, and deep radial valleys
	 * separated by knife ridges are the most recognisable thing about an eroded shield:
	 * the Na Pali coast is what Kauai looks like after this has run for five million
	 * years.
	 */
	private static final double GROOVE_DEPTH = 0.55;

	/**
	 * How much deeper the grooves cut on the oldest shield in a chain than on the
	 * active one.
	 *
	 * <p>Age is the whole story of a chain, and until now it only took height away. An
	 * active shield is a month-old lava flow with no valleys in it at all; five million
	 * years later the same cone is the Na Pali coast. Giving erosion its own axis means
	 * the far end of a chain differs from the near end in shape and not only in size.
	 */
	private static final double GROOVE_AGEING = 1.5;

	/**
	 * Radius of the circle in noise space the field is read on.
	 *
	 * <p>Sets how many valleys run down the shield: the circle's circumference is
	 * {@code 2 pi} times this and the base octave turns over once per unit, so this is
	 * the valley count over about six. Sixteen valleys on a 2,100-block shield puts
	 * their mouths some 800 blocks apart at the coast, which is a valley a player walks
	 * along rather than steps over.
	 */
	private static final double GROOVE_COUNT = 2.5;

	/**
	 * Radius within which the grooves stop converging, as a fraction of the shield's.
	 *
	 * <p><b>Without it the summit is a point of unbounded spatial frequency, and the
	 * section showed exactly that:</b> a smooth cone with a cluster of 100-block spikes
	 * 150 blocks tall around its crest. Reading the field at a unit direction makes a
	 * valley a ray, which is what a valley on a cone should be, but it also means a
	 * straight line passing near the centre sweeps the entire angular range within a
	 * few blocks. The valleys are then finer than any resolution can hold, and no depth
	 * fade fixes it, because the fade falls off linearly while the frequency climbs as
	 * the reciprocal.
	 *
	 * <p>Clamping the divisor stops the convergence: inside this radius the sample
	 * point moves with position rather than with direction, so the frequency is bounded
	 * by the value it has at the clamp. The grooves have already faded to a third of
	 * their depth by then, and the crater is where a real cone's radial valleys give
	 * out anyway, since there is no catchment above them.
	 */
	private static final double GROOVE_CORE = 0.3;

	/** Octaves in the groove field. Two: a valley, and a notch in its wall. */
	private static final int GROOVE_OCTAVES = 2;

	/**
	 * How sharply the grooves are pinched, as an exponent on the ridged field.
	 *
	 * <p>The same trick and the same reason as {@code MountainRidge.GRAIN_SHARPNESS}:
	 * above one it narrows the ridges and widens the valleys, which is the difference
	 * between a fluted cone and a wavy one.
	 */
	private static final double GROOVE_SHARPNESS = 1.5;

	private final long seed;
	private final PlateMap plates;
	private final TerrainSettings.Hotspots settings;
	private final PoissonDisk lattice;
	private final double reachBlocks;
	private final double trackBlocks;
	private final double shieldRadiusBlocks;
	private final double chainSpacingBlocks;
	private final double domeRadiusBlocks;
	private final double calderaRadiusBlocks;
	private final double trackHalfWidthBlocks;
	private final FractalNoise2D shape;
	private final FractalNoise2D groove;
	private final ThreadLocal<Hotspot[]> cache;

	public HotspotField(final long seed, final PlateMap plates, final TerrainSettings.Hotspots settings) {
		double spacing = plates.settings().crustSpacingBlocks();

		this.seed = seed;
		this.plates = plates;
		this.settings = settings;
		this.lattice = new PoissonDisk(seed ^ SALT_LATTICE, spacing * settings.spacingFraction(), JITTER);
		this.trackBlocks = spacing * settings.trackFraction();
		this.shieldRadiusBlocks = spacing * settings.shieldRadiusFraction();
		this.chainSpacingBlocks = spacing * settings.chainSpacingFraction();
		this.domeRadiusBlocks = spacing * settings.domeRadiusFraction();
		this.calderaRadiusBlocks = spacing * settings.calderaRadiusFraction();
		this.trackHalfWidthBlocks = spacing * settings.trackHalfWidthFraction();

		// Sampled in world coordinates rather than in each feature's own frame, so two
		// shields whose flanks meet agree about the ground where they meet. A field per
		// feature would put a seam along every such join.
		this.shape = FractalNoise2D.standard(
				seed ^ SALT_SHAPE, SHAPE_OCTAVES, shieldRadiusBlocks * SHAPE_WAVELENGTH);

		// Unit wavelength, because this one is fed a direction rather than a position.
		// See fluting, where the coordinates are built.
		this.groove = FractalNoise2D.standard(seed ^ SALT_GROOVE, GROOVE_OCTAVES, 1.0);

		// The furthest any relief can lie from its plume: the end of the longest trail,
		// plus whichever is wider there of a shield thrown off the axis or the plain
		// around a continental track, or the swell around the plume itself. An
		// overestimate, so the search can reject on one subtraction without ever
		// clipping a feature.
		this.reachBlocks = Math.max(
				domeRadiusBlocks * (1.0 + SHAPE_DEPTH),
				trackBlocks + Math.max(
						shieldRadiusBlocks * (1.0 + SHAPE_DEPTH)
								+ CHAIN_ACROSS_JITTER * chainSpacingBlocks,
						trackHalfWidthBlocks));

		if (reachBlocks >= lattice.spacing()) {
			throw new IllegalArgumentException(String.format(
					"hotspot relief reaches %.0f blocks but plumes are only %.0f apart;"
							+ " the search covers one ring of cells and would clip",
					reachBlocks, lattice.spacing()));
		}

		this.cache = ThreadLocal.withInitial(() -> new Hotspot[CACHE_SLOTS]);
	}

	/**
	 * One plume, resolved.
	 *
	 * @param cellX       lattice column
	 * @param cellZ       lattice row
	 * @param present     whether this cell holds a plume at all. Absent cells are
	 *                    cached too, so the question is asked once
	 * @param x           world x of the plume
	 * @param z           world z of the plume
	 * @param continental whether the crust over the plume is continental, read at the
	 *                    plume rather than at any column
	 * @param directionX  unit vector along the plate's motion, which is where the
	 *                    trail runs
	 * @param directionZ  the other component of it
	 * @param trackBlocks length of the trail, scaled by how fast the plate moves
	 * @param strength    in {@code [MIN_STRENGTH, 1]}, scaling every height this plume
	 *                    builds
	 */
	public record Hotspot(
			long cellX, long cellZ, boolean present,
			double x, double z,
			boolean continental,
			double directionX, double directionZ,
			double trackBlocks, double strength) {

		/** Stable identity, for draws that must differ per plume. */
		long id(final long seed) {
			return Hashing.hash(seed, cellX, cellZ);
		}

		/** Position along the trail, in blocks: zero at the plume, positive with age. */
		double along(final double offsetX, final double offsetZ) {
			return offsetX * directionX + offsetZ * directionZ;
		}

		/** Signed distance off the trail's axis. */
		double across(final double offsetX, final double offsetZ) {
			return offsetX * -directionZ + offsetZ * directionX;
		}
	}

	/** Receives one volcano at a time from {@link #forEachVolcano}. */
	@FunctionalInterface
	public interface VolcanoVisitor {
		/**
		 * @param worldX centre of the shield
		 * @param worldZ centre of the shield
		 * @param age    0 at the plume, approaching 1 at the end of the trail
		 * @param radius footprint radius in blocks
		 * @param rise   height of the summit above the crust base, in blocks
		 */
		void visit(double worldX, double worldZ, double age, double radius, double rise);
	}

	public PoissonDisk lattice() {
		return lattice;
	}

	/**
	 * Relief from every plume in reach of a position.
	 *
	 * <p>Plumes are further apart than any of their relief reaches, so one ring of
	 * lattice cells around the query is enough, and each is rejected on distance
	 * before its plate is looked up.
	 */
	public double reliefAt(final double worldX, final double worldZ) {
		long centreX = lattice.cellX(worldX);
		long centreZ = lattice.cellZ(worldZ);
		double relief = 0.0;

		for (long cellZ = centreZ - 1; cellZ <= centreZ + 1; cellZ++) {
			for (long cellX = centreX - 1; cellX <= centreX + 1; cellX++) {
				double offsetX = worldX - lattice.pointX(cellX, cellZ);
				double offsetZ = worldZ - lattice.pointZ(cellX, cellZ);

				if (offsetX * offsetX + offsetZ * offsetZ >= reachBlocks * reachBlocks) {
					continue;
				}

				Hotspot plume = hotspot(cellX, cellZ);

				if (!plume.present()) {
					continue;
				}

				relief += plume.continental()
						? swell(plume, offsetX, offsetZ)
						: chain(plume, offsetX, offsetZ);
			}
		}

		return relief;
	}

	/**
	 * Visits every plume whose lattice cell overlaps a box, present or not.
	 *
	 * <p>For probes and renders that want to name plumes rather than sample ground.
	 */
	public void forEachHotspot(
			final double minX, final double minZ, final double maxX, final double maxZ,
			final java.util.function.Consumer<Hotspot> visitor) {
		for (long cellZ = lattice.cellZ(minZ); cellZ <= lattice.cellZ(maxZ); cellZ++) {
			for (long cellX = lattice.cellX(minX); cellX <= lattice.cellX(maxX); cellX++) {
				visitor.accept(hotspot(cellX, cellZ));
			}
		}
	}

	/**
	 * Visits each shield along an oceanic plume's chain, youngest first.
	 *
	 * <p>The same geometry {@link #reliefAt} builds from, through the same helpers, so
	 * a probe walking the chain measures the chain that was built. A continental plume
	 * has no chain and visits nothing.
	 */
	public void forEachVolcano(final Hotspot plume, final VolcanoVisitor visitor) {
		if (!plume.present() || plume.continental()) {
			return;
		}

		long id = plume.id(seed);
		int count = shieldCount(plume);

		for (int i = 0; i < count; i++) {
			double along = shieldAlong(id, i);
			double age = along / plume.trackBlocks();

			if (age >= 1.0) {
				continue;
			}

			double across = shieldAcross(id, i);

			visitor.visit(
					plume.x() + plume.directionX() * along - plume.directionZ() * across,
					plume.z() + plume.directionZ() * along + plume.directionX() * across,
					age, shieldRadius(age), shieldRise(plume, id, i, age));
		}
	}

	/**
	 * A chain of shields, active over the plume and ageing along the trail.
	 *
	 * <p>Each shield is one dome. They are summed rather than blended because two
	 * shields that overlap really are two volcanoes whose flanks meet, and where the
	 * domes overlap at all the older one has already fallen to a fraction of the
	 * younger, so the sum never rises past the taller summit by anything visible.
	 */
	private double chain(final Hotspot plume, final double offsetX, final double offsetZ) {
		double u = plume.along(offsetX, offsetZ);
		double w = plume.across(offsetX, offsetZ);
		long id = plume.id(seed);
		int count = shieldCount(plume);
		double relief = 0.0;

		// The query in world coordinates, which is where the shape field is read. The
		// offsets arrive relative to the plume, and u and w are in the chain's own
		// rotated frame, so neither is the world position the field wants.
		double worldX = plume.x() + offsetX;
		double worldZ = plume.z() + offsetZ;

		for (int i = 0; i < count; i++) {
			double along = shieldAlong(id, i);
			double age = along / plume.trackBlocks();

			if (age >= 1.0) {
				continue;
			}

			double du = u - along;
			double dw = w - shieldAcross(id, i);
			double distance = Math.sqrt(du * du + dw * dw);

			double radius = lobed(shieldRadius(age), worldX, worldZ);

			if (distance >= radius) {
				continue;
			}

			relief += shieldRise(plume, id, i, age)
					* MountainRidge.domeAt(distance, radius)
					* fluting(du, dw, distance, radius, age);
		}

		return relief;
	}

	/** How many shields the trail has room for, counting the active one. */
	private int shieldCount(final Hotspot plume) {
		return (int) Math.ceil(plume.trackBlocks() / chainSpacingBlocks);
	}

	/**
	 * Where the i-th shield sits along the trail.
	 *
	 * <p>The active shield sits exactly over the plume; every other one is jittered
	 * by less than half a spacing, so the order along the chain is preserved and no
	 * two shields land on each other.
	 */
	private double shieldAlong(final long id, final int i) {
		if (i == 0) {
			return 0.0;
		}

		return (i + (Hashing.unitDouble(id, i, 0, SALT_ALONG) - 0.5) * CHAIN_ALONG_JITTER)
				* chainSpacingBlocks;
	}

	/** Signed offset of the i-th shield off the trail's axis. The active one is on it. */
	private double shieldAcross(final long id, final int i) {
		if (i == 0) {
			return 0.0;
		}

		return (Hashing.unitDouble(id, i, 0, SALT_ACROSS) - 0.5) * 2.0
				* CHAIN_ACROSS_JITTER * chainSpacingBlocks;
	}

	private double shieldRadius(final double age) {
		return shieldRadiusBlocks * (1.0 - SHIELD_SHRINK * age);
	}

	/**
	 * Radial valleys and the ridges between them, as a multiplier in
	 * {@code [1 - depth, 1]}.
	 *
	 * <p><b>The field is read at a direction, not at a position.</b> Feeding it the unit
	 * vector from the shield's centre makes its value constant along every ray and
	 * varying only around the cone, which is exactly a set of radial valleys, and it
	 * does it without ever computing an angle. An angle would need {@code atan2} and
	 * would carry a seam at the cut where the angle wraps, which on a volcano is one
	 * valley wall a hundred blocks tall and infinitely thin. A circle in the plane has
	 * no such cut.
	 *
	 * <p>Depth grows with distance from the summit, so the grooves are shallowest at the
	 * crater and deepest at the coast. Against a dome that is falling away at the same
	 * time, the largest cut in blocks lands near mid-flank, which is where a real
	 * shield's valleys are deepest. It is also what continuity requires: at the summit
	 * every direction meets, so anything varying by direction has to fade out before it
	 * gets there.
	 */
	private double fluting(
			final double du, final double dw,
			final double distance, final double radius, final double age) {
		double scale = GROOVE_COUNT / Math.max(distance, radius * GROOVE_CORE);
		double ridged = 1.0 - Math.abs(groove.sample(du * scale, dw * scale));
		double crest = Math.pow(Math.max(0.0, ridged), GROOVE_SHARPNESS);

		double depth = Math.min(1.0, GROOVE_DEPTH * (1.0 + GROOVE_AGEING * age))
				* (distance / radius);

		return 1.0 - depth * (1.0 - crest);
	}

	/**
	 * A radius that varies with position, so the outline it draws is not a circle.
	 *
	 * <p>Read at the <b>query</b> rather than at the feature's centre, which is what
	 * makes it a shape rather than a size. Read at the centre it would be one number
	 * per feature and every island would still be round, just roundly various.
	 *
	 * <p>Continuous because both the field and {@code domeAt} are continuous in every
	 * argument, so a lobe grows out of a flank rather than being bolted to it.
	 */
	private double lobed(final double radius, final double worldX, final double worldZ) {
		return radius * (1.0 + SHAPE_DEPTH * shape.sample(worldX, worldZ));
	}

	/**
	 * Summit height of the i-th shield above the crust base.
	 *
	 * <p>Age does most of it. The active shield is the ceiling, and each older one is
	 * lower by the falloff, with a per-shield draw below that so the chain is not a
	 * perfectly geometric staircase.
	 */
	private double shieldRise(final Hotspot plume, final long id, final int i, final double age) {
		double size = i == 0
				? 1.0
				: 1.0 - SIZE_JITTER * Hashing.unitDouble(id, i, 0, SALT_SIZE);

		return plume.strength() * settings.shieldRise() * Math.pow(1.0 - age, AGE_FALLOFF) * size;
	}

	/**
	 * A continental swell with a caldera in it, trailing a plain of old flows.
	 *
	 * <p>The swell is one broad dome and the caldera one narrow dip out of its top.
	 * The plain begins under the swell's downstream flank and runs out along the trail,
	 * fading in and out over its own half-width, so the swell on that side simply
	 * subsides into the plain rather than ending against it.
	 *
	 * <p><b>Fading in over the plain's width rather than the swell's is not cosmetic.</b>
	 * The swell's radius is 7,800 blocks and a slow plate's whole trail is 6,300, so a
	 * plain that took the swell's radius to reach full depth never reached it at all:
	 * measured, the ground halfway along one track sat 17 blocks low where the setting
	 * asks for 40, and the Snake River Plain would have been a hint rather than a plain.
	 */
	private double swell(final Hotspot plume, final double offsetX, final double offsetZ) {
		double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);

		double worldX = plume.x() + offsetX;
		double worldZ = plume.z() + offsetZ;

		double relief = plume.strength() * settings.domeRise()
				* MountainRidge.domeAt(distance, lobed(domeRadiusBlocks, worldX, worldZ))
				- settings.calderaDrop()
						* MountainRidge.domeAt(distance, lobed(calderaRadiusBlocks, worldX, worldZ));

		double u = plume.along(offsetX, offsetZ);
		double w = Math.abs(plume.across(offsetX, offsetZ));

		if (u > 0.0 && u < plume.trackBlocks() && w < trackHalfWidthBlocks) {
			double onset = TectonicHeight.smoothstep(u / trackHalfWidthBlocks);
			double end = MountainRidge.domeAt(
					Math.max(0.0, u - (plume.trackBlocks() - trackHalfWidthBlocks)),
					trackHalfWidthBlocks);

			relief -= settings.trackDrop() * MountainRidge.domeAt(w, trackHalfWidthBlocks)
					* onset * end;
		}

		return relief;
	}

	/** The plume in a lattice cell, resolved once per thread and reused. */
	private Hotspot hotspot(final long cellX, final long cellZ) {
		Hotspot[] slots = cache.get();
		int slot = (int) (Hashing.hash(seed, cellX, cellZ) & (CACHE_SLOTS - 1));
		Hotspot cached = slots[slot];

		if (cached != null && cached.cellX() == cellX && cached.cellZ() == cellZ) {
			return cached;
		}

		Hotspot resolved = resolve(cellX, cellZ);
		slots[slot] = resolved;

		return resolved;
	}

	/**
	 * Everything about one plume, read at the plume.
	 *
	 * <p>The plate lookup here is the same one {@link PlateMap#sample} makes for a
	 * column: warp the position, find its crust cell, find that cell's plate. It is
	 * the only expensive thing in this class, which is why its answer is cached.
	 */
	private Hotspot resolve(final long cellX, final long cellZ) {
		double x = lattice.pointX(cellX, cellZ);
		double z = lattice.pointZ(cellX, cellZ);

		if (Hashing.unitDouble(seed, cellX, cellZ, SALT_PRESENCE) >= settings.density()) {
			return new Hotspot(cellX, cellZ, false, x, z, false, 1.0, 0.0, 0.0, 0.0);
		}

		VoronoiSample cell = plates.voronoi().sample(
				plates.warp().warpX(x, z), plates.warp().warpZ(x, z));
		Plate plate = plates.plateOf(cell.cellX(), cell.cellZ());
		CrustCell crust = plates.crustCellAt(cell.cellX(), cell.cellZ());

		// Speed is bounded away from zero by the plate map, so the direction is
		// always defined and the shortest trail is a third of the longest.
		double speed = Math.hypot(plate.motionX(), plate.motionZ());

		double strength = MIN_STRENGTH + (1.0 - MIN_STRENGTH)
				* Hashing.unitDouble(seed, cellX, cellZ, SALT_STRENGTH);

		return new Hotspot(
				cellX, cellZ, true, x, z, crust.isContinental(),
				plate.motionX() / speed, plate.motionZ() / speed,
				trackBlocks * speed, strength);
	}
}
