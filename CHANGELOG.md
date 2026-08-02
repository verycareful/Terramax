# Changelog

All notable changes to Terramax are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses plain [semantic versioning](https://semver.org/spec/v2.0.0.html). Fabric resolves mod dependencies with semver, so version strings stay in `MAJOR.MINOR.PATCH` form.

## [Unreleased]

Version `0.1.0` is in development. Nothing has been released yet, and the mod is not playable.

### Added

- Fabric mod scaffold targeting Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, on Java 25.
- `terramax:steppe` biome: cold, dry, open grassland. Tall grass only, with no trees, flowers, or extra vegetation. Precipitation disabled so it reads as arid rather than merely cold. Horses and rabbits are the only passive spawns.
- Datagen pipeline emitting biome definitions to `src/main/generated`.
- `/terramax locate <biome> [radius] [step]`, a biome search that reaches far beyond vanilla's `/locate biome`. Vanilla hardcodes `MAX_BIOME_SEARCH_RADIUS = 6400` with no way to raise it, which is unusable at the scales Terramax targets. Defaults to 64000 blocks at step 64, and takes a radius up to 10,000,000.
- GitHub Actions workflow building on Ubuntu with JDK 25.

### Notes

Terramax will not use Minecraft's default world generator. Work is under way on a tectonic plate model behind a custom world type, with terrain built and tuned in a standalone simulator rather than in-game. Until that lands, the steppe biome exists mainly as a target for verifying placement.

An earlier approach that layered custom biomes onto vanilla's generator was abandoned before release. It could not deliver most of the intended biome set: a biome cannot control its own terrain shape in Minecraft, so landforms such as fjords, highlands and foothills are not expressible as climate niches. None of that work reached a released version.
