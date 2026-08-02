package com.fury.terramax.client.datagen;

import java.util.concurrent.CompletableFuture;

import com.fury.terramax.biome.TerramaxBiomes;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricDynamicRegistryProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;

/**
 * Emits Terramax's biome definitions to {@code data/terramax/worldgen/biome/}.
 *
 * <p>Only Terramax's own keys are added. {@code entries.addAll} would re-emit
 * every vanilla biome alongside them.
 */
public class BiomeProvider extends FabricDynamicRegistryProvider {
	public BiomeProvider(final FabricPackOutput output, final CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void configure(final HolderLookup.Provider registries, final Entries entries) {
		entries.add(registries.lookupOrThrow(Registries.BIOME), TerramaxBiomes.STEPPE);
	}

	@Override
	public String getName() {
		return "Terramax Biomes";
	}
}
