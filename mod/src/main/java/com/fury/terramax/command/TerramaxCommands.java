package com.fury.terramax.command;

import com.google.common.base.Stopwatch;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;

/**
 * Development commands for Terramax.
 *
 * <p>{@code /terramax locate <biome> [radius] [step]} exists because vanilla's
 * {@code /locate biome} hardcodes {@code MAX_BIOME_SEARCH_RADIUS = 6400} with no
 * way to raise it. That radius is far too small once biome scaling is applied:
 * at 16x, 6400 blocks covers roughly what 400 blocks covers in vanilla, so a
 * correctly placed biome is still effectively unfindable.
 */
public final class TerramaxCommands {
	/** Ten times vanilla's radius. Still returns in reasonable time at the default step. */
	private static final int DEFAULT_RADIUS = 64000;

	/**
	 * Vanilla uses 32. A coarser step covers far more ground for the same number
	 * of samples, at the cost of possibly skipping over very small biomes.
	 */
	private static final int DEFAULT_HORIZONTAL_STEP = 64;

	private static final int VERTICAL_STEP = 64;

	private static final int MAX_RADIUS = 10_000_000;
	private static final int MAX_STEP = 4096;

	private TerramaxCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
				dispatcher.register(Commands.literal("terramax")
						// 26.2 replaced hasPermission(int) with a PermissionSet system.
						// Same gate vanilla puts on /locate.
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("locate")
								.then(Commands.argument("biome",
												ResourceOrTagArgument.resourceOrTag(buildContext, Registries.BIOME))
										.executes(ctx -> locate(ctx, DEFAULT_RADIUS, DEFAULT_HORIZONTAL_STEP))
										.then(Commands.argument("radius",
														IntegerArgumentType.integer(1, MAX_RADIUS))
												.executes(ctx -> locate(ctx,
														IntegerArgumentType.getInteger(ctx, "radius"),
														DEFAULT_HORIZONTAL_STEP))
												.then(Commands.argument("step",
																IntegerArgumentType.integer(1, MAX_STEP))
														.executes(ctx -> locate(ctx,
																IntegerArgumentType.getInteger(ctx, "radius"),
																IntegerArgumentType.getInteger(ctx, "step")))))))));
	}

	private static int locate(final CommandContext<CommandSourceStack> ctx, final int radius, final int step)
			throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ResourceOrTagArgument.Result<Biome> target =
				ResourceOrTagArgument.getResourceOrTag(ctx, "biome", Registries.BIOME);

		BlockPos origin = BlockPos.containing(source.getPosition());

		source.sendSystemMessage(Component.literal(
				"Searching for " + target.asPrintable() + " within " + radius + " blocks (step " + step + ")..."));

		Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
		Pair<BlockPos, Holder<Biome>> nearest =
				source.getLevel().findClosestBiome3d(target, origin, radius, step, VERTICAL_STEP);
		stopwatch.stop();

		if (nearest == null) {
			source.sendFailure(Component.literal(
					"No " + target.asPrintable() + " within " + radius + " blocks. Searched in "
							+ stopwatch.elapsed().toSeconds() + "s."));
			return 0;
		}

		BlockPos found = nearest.getFirst();
		int distance = (int) Math.sqrt(found.distSqr(origin));

		source.sendSuccess(() -> Component.literal(
				"Found " + nearest.getSecond().getRegisteredName()
						+ " at " + found.getX() + " ~ " + found.getZ()
						+ " (" + distance + " blocks away, " + stopwatch.elapsed().toSeconds() + "s)"), false);

		return 1;
	}
}
