package com.alex.test.command;

import com.alex.test.block.PianoLock;
import com.alex.test.block.PianoOwner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.stream.Collectors;

/**
 * {@code /villager pianoowner [list]}: who counts as an owner of ZalithYT's Piano ({@link PianoLock}) and why. Look
 * only (the on/off test switch was taken out 10-24, user). Only in a development run: the release jar never
 * registers it ({@link FMLEnvironment#production}).
 */
public final class PianoOwnerCommand {
    private PianoOwnerCommand() {}

    /** Brigadier joins this onto the {@code /villager} that VillagerCommands registered. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (FMLEnvironment.production) return;
        dispatcher.register(Commands.literal("villager")
                .then(Commands.literal("pianoowner")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> list(ctx.getSource()))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String owners = PianoOwner.OWNERS.stream()
                .map(owner -> owner.name() + " " + owner.id())
                .collect(Collectors.joining(", "));
        boolean checked = PianoLock.accountsChecked(player);
        String verdict = PianoLock.isOwner(player) ? "an owner" : "a stranger";
        source.sendSuccess(() -> Component.literal("Piano owners: " + owners), false);
        source.sendSuccess(() -> Component.literal(checked
                ? "This server checks accounts: the id must be on the list."
                : "This server does not check accounts: the name must be on the list."), false);
        source.sendSuccess(() -> Component.literal("You: " + player.getGameProfile().getName() + " " + player.getUUID()
                + ". You count as " + verdict + "."), false);
        return 1;
    }
}
