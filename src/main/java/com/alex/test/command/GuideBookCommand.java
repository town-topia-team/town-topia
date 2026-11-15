package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * {@code /villager guide}: a fresh copy of the starter guide book.
 *
 * <p>Every player gets the book once on first join through the hidden
 * {@code guide_book} advancement; this rolls the same loot table so the
 * command copy always matches. No permission needed, like the recipe.
 */
public final class GuideBookCommand {
    private static final ResourceKey<LootTable> GUIDE_BOOK = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "guide_book"));

    private GuideBookCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("guide")
                .executes(ctx -> give(ctx.getSource()));
    }

    private static int give(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can receive the guide book."));
            return 0;
        }
        LootParams params = new LootParams.Builder(player.serverLevel())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .create(LootContextParamSets.ADVANCEMENT_REWARD);
        int given = 0;
        for (ItemStack stack : player.server.reloadableRegistries().getLootTable(GUIDE_BOOK)
                .getRandomItems(params)) {
            if (!player.addItem(stack)) player.drop(stack, false);
            given++;
        }
        if (given == 0) {
            source.sendFailure(Component.literal("The guide book could not be made."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Here is your TownTopia guide."), false);
        return 1;
    }
}
