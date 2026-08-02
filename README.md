# Terramax

A Fabric mod for Minecraft **26.2** that generates worlds from a simulated tectonic plate model, then places grounded, earthlike biomes on the terrain it produces.

Terramax does not use Minecraft's default world generator. It ships its own world type, selectable at world creation. Vanilla worlds are untouched.

> **Status: early development.** Terrain generation is being built now. Nothing here is playable yet.

## What it does

Terrain begins with tectonic plates: centres scattered by Poisson disk sampling, cells resolved as a Voronoi diagram, and relief driven by what happens at the boundaries between them. Convergent boundaries raise mountains, divergent boundaries open rifts, transform boundaries shear flat. Domain warping is applied before every plate lookup so the result reads as coastline rather than as polygons.

Plate centres sit roughly 100,000 blocks apart, and the dimension runs from y=-256 to y=1792. Together those give continental proportions and real alpine relief, at the cost of a chunk generator that has to be careful about what it evaluates.

Biomes are then placed from plate type, elevation and erosion rather than from vanilla's climate noise. This matters because a biome cannot control its own terrain shape in Minecraft: landforms like fjords, highlands and foothills are terrain features, not climate niches, and no amount of temperature and humidity tuning will produce them.

## Building

Requires **JDK 25 or newer**. Minecraft 26.2 runs on Java 25, and the build targets `release 25`.

```
./gradlew build          # produce the mod jar
./gradlew runDatagen     # regenerate data under src/main/generated
./gradlew runClient      # launch the game with the mod
```

If `JAVA_HOME` points at an older JDK, override it for the invocation rather than changing it globally:

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

## Repository layout

```
src/main/java/com/fury/terramax/
├── biome/      biome definitions: surface, tints, features, spawns
└── command/    development commands

src/main/generated/    datagen output, committed
```

The terrain work introduces a three-module split (`core`, `sim`, `mod`). `core` will hold the terrain mathematics with no Minecraft dependency, so a standalone simulator can render and tune it without launching the game. At 100,000-block plate spacing, in-game iteration is impractical: you would fly 50,000 blocks to reach a boundary.

## Development notes

`/terramax locate <biome> [radius] [step]` searches far beyond vanilla's `/locate biome`, which hardcodes a 6400-block limit. That limit is unusable at Terramax's scales.

Minecraft 26.2 uses calendar versioning (1.21.11 was the last release of the old scheme) and Mojang-derived mappings. **Yarn no longer exists past 1.21.11**, so code written against 1.21 needs every Minecraft symbol re-resolved, not merely recompiled.

Further conventions are in [CLAUDE.md](CLAUDE.md).

## Lineage

Terramax succeeds `realworld` (Minecraft 1.21.8), which enlarged vanilla's Large Biomes preset to 16x by overriding its noise parameters. That approach is not carried forward: overriding vanilla's generator can only rescale what vanilla already produces, and Terramax replaces it outright.

## License

Apache-2.0. See [LICENSE.txt](LICENSE.txt).
