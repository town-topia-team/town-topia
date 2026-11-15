package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;

/**
 * Where the player put a blueprint on the Builder's desk: the turned and mirrored
 * blueprint's lowest corner in the world, and whether the desk thinks it can go there.
 *
 * <p>The desk works this out on the client from its snapshot (border, building shells)
 * so the player sees red boxes at once; ordering (round 4) sends origin, rotation and
 * mirror, and the server checks again before it accepts anything. {@code reason} is the
 * plain sentence shown when {@code valid} is false.
 */
public record BlueprintPlacement(String blueprintName, BlockPos origin, Rotation rotation, Mirror mirror,
                                 boolean valid, @Nullable String reason) {
}
