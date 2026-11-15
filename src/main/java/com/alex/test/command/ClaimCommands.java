package com.alex.test.command;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.claims.VillageClaim;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * {@code /villager claim}: operators' tools for village claims (docs/plans/2026-10-24-village-claims.md, round 1).
 * <ul>
 *   <li>{@code claim}: the world's switch and who owns the village you stand in.</li>
 *   <li>{@code claim list}: every village and its owner.</li>
 *   <li>{@code claim owner <player>} / {@code claim clear}: give the village you stand in to a player, or to nobody.</li>
 *   <li>{@code claim on|off}: the world's switch, saved with the world.</li>
 *   <li>{@code claim bypass}: you may build and break in every village (round 5), until you run it again or the
 *   server stops (not saved).</li>
 * </ul>
 * "The village you stand in" is the one whose land you are on, paused or not, else the nearest Town Hall within
 * {@link #NEAR_BLOCKS} blocks.
 */
public final class ClaimCommands {
    private ClaimCommands() {}

    /** A village's land reaches 9 chunks out from its Town Hall's chunk: about this far from its door. */
    private static final int NEAR_BLOCKS = 160;

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("claim")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(Commands.literal("bypass").executes(ctx -> toggleBypass(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> clearOwner(ctx.getSource())))
                .then(Commands.literal("owner")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> setOwner(ctx.getSource(),
                                        GameProfileArgument.getGameProfiles(ctx, "player")))));
    }

    private static int show(CommandSourceStack source) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(source.getServer());
        boolean bypass = source.getPlayer() != null && ClaimLock.hasBypass(source.getPlayer().getUUID());
        String switchLine = "Village claims are " + (registry.claimsEnabled() ? "on" : "off") + " in this world."
                + (bypass ? " Your claim bypass is on." : "");
        VillageManager village = villageAt(source);
        String villageLine = village == null
                ? "You are not in a village."
                : describe(village, registry);
        source.sendSuccess(() -> Component.literal(switchLine + "\n" + villageLine), false);
        return village != null && village.claim().hasOwner() ? 1 : 0;
    }

    private static int list(CommandSourceStack source) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(source.getServer());
        Collection<VillageManager> villages = registry.all();
        if (villages.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No villages in this world."), false);
            return 0;
        }
        StringBuilder text = new StringBuilder(villages.size() + " village(s), claims "
                + (registry.claimsEnabled() ? "on" : "off") + ":");
        for (VillageManager village : villages) {
            String dimension = village.getLevel() == null ? "?" : village.getLevel().dimension().location().getPath();
            text.append("\n- ").append(describe(village, registry)).append(" [").append(dimension).append("]");
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return villages.size();
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        VillageManagerRegistry.get(source.getServer()).setClaimsEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Village claims are now " + (enabled ? "on" : "off")
                + " in this world. Owners are remembered either way."), true);
        return enabled ? 1 : 0;
    }

    private static int toggleBypass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean on = ClaimLock.toggleBypass(player);
        source.sendSuccess(() -> Component.literal(on
                ? "Claim bypass on: you may build and break in every village. Run it again to switch it off."
                : "Claim bypass off: village land is locked for you again where your rank says so."), true);
        return on ? 1 : 0;
    }

    private static int setOwner(CommandSourceStack source, Collection<GameProfile> profiles) {
        if (profiles.size() != 1) {
            source.sendFailure(Component.literal("Name exactly one player."));
            return 0;
        }
        VillageManager village = villageAt(source);
        if (village == null) {
            source.sendFailure(Component.literal("Stand in the village first (or near its Town Hall)."));
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        village.claim().setOwner(profile.getId(), profile.getName());
        village.markPersistentDataDirty();
        source.sendSuccess(() -> Component.literal(profile.getName() + " now owns village "
                + shortId(village) + "."), true);
        return 1;
    }

    private static int clearOwner(CommandSourceStack source) {
        VillageManager village = villageAt(source);
        if (village == null) {
            source.sendFailure(Component.literal("Stand in the village first (or near its Town Hall)."));
            return 0;
        }
        VillageClaim claim = village.claim();
        if (!claim.hasOwner()) {
            source.sendFailure(Component.literal("Village " + shortId(village) + " has no owner already."));
            return 0;
        }
        String was = claim.ownerName();
        claim.clearOwner();
        village.markPersistentDataDirty();
        source.sendSuccess(() -> Component.literal("Village " + shortId(village) + " has no owner now (was "
                + was + ")."), true);
        return 1;
    }

    /** "Village 1a2b3c4d, Town Hall at 10, 64, -20: owned by Anna" (paused / no owner yet as they apply). */
    private static String describe(VillageManager village, VillageManagerRegistry registry) {
        BlockPos hall = village.getBellPos();
        VillageClaim claim = village.claim();
        return "Village " + shortId(village) + ", Town Hall at " + hall.getX() + ", " + hall.getY() + ", "
                + hall.getZ() + (registry.isVillageInactive(village.getVillageId()) ? " (paused)" : "") + ": "
                + (claim.hasOwner() ? "owned by " + claim.ownerName() : "no owner yet");
    }

    @Nullable
    private static VillageManager villageAt(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager nearest = null;
        double best = (double) NEAR_BLOCKS * NEAR_BLOCKS;
        for (VillageManager village : VillageManagerRegistry.get(source.getServer()).all()) {
            if (village.getLevel() == null || !village.getLevel().dimension().equals(level.dimension())) continue;
            if (village.contains(pos)) return village;
            double distance = village.getBellPos().distSqr(pos);
            if (distance <= best) {
                best = distance;
                nearest = village;
            }
        }
        return nearest;
    }

    private static String shortId(VillageManager village) {
        return village.getVillageId().toString().substring(0, 8);
    }
}
