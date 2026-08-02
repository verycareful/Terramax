package com.fury.terramax.client;

import com.fury.terramax.biome.TerramaxBiomes;
import com.fury.terramax.client.datagen.BiomeProvider;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;

public class TerramaxDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(final FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();

		pack.addProvider(BiomeProvider::new);
	}

	@Override
	public void buildRegistry(final RegistrySetBuilder registryBuilder) {
		registryBuilder.add(Registries.BIOME, TerramaxBiomes::bootstrap);
	}
}
