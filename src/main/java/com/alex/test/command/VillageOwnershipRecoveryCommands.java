package com.alex.test.command;

import com.alex.test.ModItems;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Explicit operator recovery for legacy goods; never scans or rewrites chest contents. */
public final class VillageOwnershipRecoveryCommands {
    private VillageOwnershipRecoveryCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("ownership")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("recover")
                        .then(Commands.argument("villageId", StringArgumentType.word())
                                .executes(ctx -> recover(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "villageId")))));
    }

    private static int recover(CommandSourceStack source, String rawId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("Use the full village UUID."));
            return 0;
        }
        VillageManager village = VillageManagerRegistry.get(source.getServer()).get(id);
        if (village == null || village.getLevel() != player.serverLevel()) {
            source.sendFailure(Component.literal("That village is not active in this dimension."));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || VillageTokenBinding.isBound(held)) {
            source.sendFailure(Component.literal("Hold one unbound legacy stack to recover."));
            return 0;
        }
        if (held.is(ModItems.TOWN_HALL.get())) {
            source.sendFailure(Component.literal("A crafted Town Hall token is bound only during founding."));
            return 0;
        }
        if (!VillageTokenBinding.bind(held, village)) return 0;
        player.getInventory().setChanged();
        source.sendSuccess(() -> Component.literal("Recovered " + held.getCount()
                + " item(s) for village "
                + village.getVillageId().toString().substring(0, 8) + "."), true);
        return 1;
    }
}
