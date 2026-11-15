package com.alex.test.command;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner;
import com.alex.test.village.villagers.builder.craft.RecipeBook;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the nearest village's Builder would do to make blocks it is short of (round 4 of
 * docs/plans/2026-10-23-builder-makes-missing-blocks.md): the steps, what it takes, the fuel,
 * and what stops it, once from the village's goods and once from players' given blocks.
 * Read only: nothing is taken or made.
 *
 * <pre>
 *   /villager craftcheck &lt;item&gt; [count]   count defaults to 64
 * </pre>
 */
public final class CraftCheckCommands {

    private static final int VILLAGE_REACH = 512;

    private CraftCheckCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("craftcheck")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                BuiltInRegistries.ITEM.keySet(), builder))
                        .executes(ctx -> check(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"), 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1024))
                                .executes(ctx -> check(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    private static int check(CommandSourceStack source, ResourceLocation key, int count) {
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("No item called " + key + "."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findNearestVillage(level, pos, VILLAGE_REACH);
        if (village == null) {
            source.sendFailure(Component.literal("No village within " + VILLAGE_REACH + " blocks."));
            return 0;
        }
        NpcEntity builder = nearestBuilder(village, pos);
        int packSlots = builder == null ? InventoryManager.SIZE : emptySlots(builder);
        MaterialPlanner.Book book = RecipeBook.get(level.getServer());
        MaterialPlanner.Stations stations = RecipeBook.stations(village);
        String id = RecipeBook.id(item);

        say(source, Component.literal(String.format(Locale.ROOT, "Craft check: %,d %s (village at %s)", count, name(id),
                village.getBellPos().toShortString())).withStyle(ChatFormatting.GOLD));
        say(source, Component.literal("Storage rooms: crafting table " + yesNo(stations.table()) + ", stonecutter "
                + yesNo(stations.stonecutter()) + ", furnace " + yesNo(stations.furnace()) + ". "
                + (builder == null ? "No Builder nearby: counting an empty pack."
                        : "The Builder's pack has " + packSlots + " empty slots.")).withStyle(ChatFormatting.GRAY));
        int asIs = BuildOrders.stored(village, level, item);
        if (asIs > 0) {
            say(source, Component.literal(String.format(Locale.ROOT,
                    "Storage already has %,d as they are: the Builder fetches those, it makes only what is missing.", asIs))
                    .withStyle(ChatFormatting.GRAY));
        }

        Map<String, Integer> ledger = RecipeBook.villageLedger(level, village, builder, false);
        MaterialPlanner.Plan plan = MaterialPlanner.plan(book, ledger, stations, id, count, packSlots);
        tell(source, "From the village's goods", plan);
        if (plan.steps().stream().anyMatch(step -> step.recipe().kind() == MaterialPlanner.Kind.SMELT)) {
            say(source, Component.literal("  The Builder doesn't smelt yet (round 6): it only makes what needs no furnace.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (!plan.any()) {
            MaterialPlanner.Plan rich = MaterialPlanner.plan(book, RecipeBook.villageLedger(level, village, builder, true),
                    stations, id, count, packSlots);
            if (rich.any()) {
                say(source, Component.literal(String.format(Locale.ROOT,
                        "  Only valuables could make it (%,d): \"Use Valuables\" comes in round 7.", rich.makes()))
                        .withStyle(ChatFormatting.YELLOW));
            }
        }

        Map<String, Integer> given = RecipeBook.givenLedger(level.getServer(), village, false);
        if (given.isEmpty()) {
            say(source, Component.literal("From given blocks: players gave nothing spare to make it from.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tell(source, "From given blocks", MaterialPlanner.plan(book, given, stations, id, count, packSlots));
        }
        DynamicVillages.LOGGER.debug("[BuilderCraft] craftcheck {} x{}: village makes {} ({}), {} steps, takes {}, fuel {}",
                id, count, plan.makes(), plan.why(), plan.steps().size(), plan.takes(), plan.fuel());
        return plan.makes();
    }

    private static void tell(CommandSourceStack source, String from, MaterialPlanner.Plan plan) {
        if (!plan.any()) {
            say(source, Component.literal(from + ": makes none, " + plan.why().words + ".").withStyle(ChatFormatting.RED));
            return;
        }
        boolean all = plan.makes() >= plan.wanted();
        say(source, Component.literal(String.format(Locale.ROOT, "%s: makes %,d of %,d%s", from, plan.makes(),
                plan.wanted(), all ? "." : ", then " + plan.why().words + "."))
                .withStyle(all ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        int n = 1;
        for (MaterialPlanner.Step step : plan.steps()) {
            MaterialPlanner.Recipe recipe = step.recipe();
            say(source, Component.literal(String.format(Locale.ROOT, "  %d. %s %s into %,d %s (%s, %,d %s)", n++,
                    recipe.kind().verb, list(step.used()), step.gives(), name(recipe.result()), recipe.kind().station,
                    step.runs(), step.runs() == 1 ? "time" : "times")));
        }
        say(source, Component.literal("  Takes: " + list(plan.takes())
                + (plan.fuel().isEmpty() ? "" : "; fuel: " + list(plan.fuel()))));
        if (!plan.leftover().isEmpty()) {
            say(source, Component.literal("  Left over: " + list(plan.leftover())).withStyle(ChatFormatting.GRAY));
        }
    }

    @Nullable
    private static NpcEntity nearestBuilder(VillageManager village, BlockPos pos) {
        NpcEntity best = null;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.BUILDER) continue;
            if (best == null || npc.distanceToSqr(pos.getCenter()) < best.distanceToSqr(pos.getCenter())) best = npc;
        }
        return best;
    }

    private static int emptySlots(NpcEntity npc) {
        int empty = 0;
        for (ItemStack stack : npc.getInventory().getSlots()) if (stack.isEmpty()) empty++;
        return empty;
    }

    /** "64 Cobblestone, 8 Coal", most first. */
    private static String list(Map<String, Integer> items) {
        if (items.isEmpty()) return "nothing";
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(items.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> each : sorted) {
            parts.add(String.format(Locale.ROOT, "%,d %s", each.getValue(), name(each.getKey())));
        }
        return String.join(", ", parts);
    }

    private static String name(String id) {
        return new ItemStack(RecipeBook.item(id)).getHoverName().getString();
    }

    private static String yesNo(boolean yes) {
        return yes ? "yes" : "no";
    }

    private static void say(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }
}
