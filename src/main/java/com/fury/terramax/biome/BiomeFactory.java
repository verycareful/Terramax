package com.fury.terramax.biome;

import net.minecraft.core.HolderGetter;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.biome.OverworldBiomes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Builds Terramax's biomes. Every biome goes through here so the grounded,
 * earthlike character stays consistent as the set grows.
 *
 * <p>Terrain flatness is deliberately absent from this class. Since 1.18 a biome
 * does not control its own terrain shape; the noise router does. A biome ends up
 * flat by being assigned climate parameters that only match flat terrain, which
 * is placement's job, not the biome definition's.
 */
public final class BiomeFactory {
	/** Cold, but above the freezing point where vanilla switches rain to snow. */
	private static final float STEPPE_TEMPERATURE = 0.25F;

	/** Arid. Drives dry grass and foliage tints. */
	private static final float STEPPE_DOWNFALL = 0.05F;

	/** Horses dominate. Open grassland is what they are for. */
	private static final int HORSE_WEIGHT = 12;

	/** Rabbits present but sparse. */
	private static final int RABBIT_WEIGHT = 4;

	private BiomeFactory() {
	}

	/**
	 * Steppe: cold, dry, open grassland. Tall grass and effectively nothing else.
	 *
	 * <p>No trees, no flowers, and no extra vegetation by design. Precipitation is
	 * off, which is what makes it read as arid rather than merely cold.
	 */
	public static Biome steppe(
			final HolderGetter<PlacedFeature> placedFeatures,
			final HolderGetter<ConfiguredWorldCarver<?>> carvers) {
		BiomeGenerationSettings.Builder generation = new BiomeGenerationSettings.Builder(placedFeatures, carvers);

		OverworldBiomes.globalOverworldGeneration(generation);
		BiomeDefaultFeatures.addDefaultOres(generation);
		BiomeDefaultFeatures.addDefaultSoftDisks(generation);

		// Savanna grass is the tall-grass-heavy set. Deliberately no addSavannaTrees,
		// no flowers, and no addDefaultExtraVegetation: the steppe is open ground.
		BiomeDefaultFeatures.addSavannaGrass(generation);
		BiomeDefaultFeatures.addSavannaExtraGrass(generation);

		// Deliberately NOT BiomeDefaultFeatures.farmAnimals: that adds cows, sheep,
		// pigs and chickens. The steppe gets horses and rabbits and nothing else.
		MobSpawnSettings.Builder mobs = new MobSpawnSettings.Builder();
		mobs.addSpawn(MobCategory.CREATURE, HORSE_WEIGHT, new MobSpawnSettings.SpawnerData(EntityTypes.HORSE, 2, 6));
		mobs.addSpawn(MobCategory.CREATURE, RABBIT_WEIGHT, new MobSpawnSettings.SpawnerData(EntityTypes.RABBIT, 2, 3));
		BiomeDefaultFeatures.commonSpawnWithZombieHorse(mobs);

		return OverworldBiomes.baseBiome(STEPPE_TEMPERATURE, STEPPE_DOWNFALL)
				.hasPrecipitation(false)
				.mobSpawnSettings(mobs.build())
				.generationSettings(generation.build())
				.build();
	}
}
