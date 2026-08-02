package com.fury.terramax.biome;

import com.fury.terramax.Terramax;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * The list of biomes Terramax adds. One constant per biome, and nothing else.
 *
 * <p>Adding a biome means adding a key here and a factory method in
 * {@link BiomeFactory}. Nothing in datagen or placement needs to change.
 */
public final class TerramaxBiomes {
	public static final ResourceKey<Biome> STEPPE = key("steppe");

	private TerramaxBiomes() {
	}

	public static void bootstrap(final BootstrapContext<Biome> context) {
		HolderGetter<PlacedFeature> placedFeatures = context.lookup(Registries.PLACED_FEATURE);
		HolderGetter<ConfiguredWorldCarver<?>> carvers = context.lookup(Registries.CONFIGURED_CARVER);

		context.register(STEPPE, BiomeFactory.steppe(placedFeatures, carvers));
	}

	private static ResourceKey<Biome> key(final String name) {
		return ResourceKey.create(Registries.BIOME, Terramax.id(name));
	}
}
