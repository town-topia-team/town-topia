package com.alex.test.command;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RanchOutside;
import com.alex.test.village.villagers.rancher.RanchRoundUp;
import com.alex.test.village.villagers.rancher.RanchStatus;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /villager ranch}: why the rancher does or does not lead the animals
 * around each pen in. For the rancher nearest the player: its lead and
 * settings, then per pen what is inside, why each kind is bred or not
 * ({@link RanchStatus}), and each stray and loose animal near the gate with
 * why it is or is not led in ({@link RanchOutside}); both ask all the village's
 * ranchers and print the Ranch window's words.
 */
final class RanchReport {

    private RanchReport() {}

    static int run(CommandContext<CommandSourceStack> cmd) {
        CommandSourceStack source = cmd.getSource();
        ServerLevel level = source.getLevel();
        Vec3 here = source.getPosition();
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, BlockPos.containing(here));
        if (village == null) {
            source.sendFailure(Component.literal("No registered village at this position."));
            return 0;
        }
        VillagerModel rancher = nearestRancher(village, here);
        if (rancher == null) {
            source.sendFailure(Component.literal("This village has no rancher."));
            return 0;
        }
        NpcEntity npc = rancher.puppet();
        Activity.Context ctx = new Activity.Context(rancher.brain(), rancher, village, level, npc);
        ProfessionSettings settings = ProfessionSettings.of(npc, RancherSettingsSchema.SCHEMA);

        Activity doing = rancher.brain() == null ? null : rancher.brain().currentActivity();
        send(source, "── Rancher " + npc.getName().getString() + " ──", ChatFormatting.GOLD);
        send(source, "  now: " + (doing == null ? "nothing" : doing.name())
                + "  |  Working: " + onOff(settings.getBool(RancherSettingsSchema.WORK_ENABLED))
                + "  |  Rounding Up: " + onOff(settings.getBool(RancherSettingsSchema.ROUNDUP_ENABLED))
                + "  |  range " + settings.getInt(RancherSettingsSchema.ROUNDUP_RANGE), ChatFormatting.GRAY);
        if (LeadSupply.hasLead(npc)) {
            send(source, "  lead: carried", ChatFormatting.GRAY);
        } else if (GetToolActivity.canAcquire(ctx, LeadSupply.REQUIREMENT)) {
            send(source, "  lead: none carried, will fetch one from storage", ChatFormatting.YELLOW);
        } else {
            send(source, "  lead: NONE, and none in storage: it cannot lead any animal. Put a lead in a chest.",
                    ChatFormatting.RED);
        }

        List<RanchBuilding> ranches = RanchRoundUp.ranches(ctx);
        if (ranches.isEmpty()) {
            send(source, "No working ranch in this village.", ChatFormatting.RED);
            return 0;
        }
        long now = level.getGameTime();
        for (RanchBuilding ranch : ranches) report(source, ctx, ranch, now);
        return 1;
    }

    private static void report(CommandSourceStack source, Activity.Context ctx, RanchBuilding ranch, long now) {
        ServerLevel level = (ServerLevel) ctx.level();
        BlockPos gate = ranch.getGatePos();
        Map<String, Integer> inside = new TreeMap<>();
        for (Animal animal : ranch.animalsInside(level)) {
            inside.merge(animal.getType().getDescription().getString(), 1, Integer::sum);
        }
        send(source, "── Ranch, gate at " + gate.toShortString() + ": " + ranch.getNavMeshBlocks().size()
                + " tiles, room for " + ranch.capacityPerSpecies() + " of each ──", ChatFormatting.GOLD);
        send(source, "  inside: " + (inside.isEmpty() ? "nothing" : inside.toString()), ChatFormatting.GRAY);
        // Breeding, every Rancher of the village asked: the same words as the Ranch window.
        RanchStatus.Report status = RanchStatus.compute(level, ctx.mgr(), ranch);
        for (RanchStatus.KindLine line : status.kinds()) {
            ChatFormatting color = switch (RanchStatus.tone(line.reason())) {
                case GOOD -> ChatFormatting.GREEN;
                case WAIT -> ChatFormatting.YELLOW;
                case BLOCKED -> ChatFormatting.RED;
            };
            Component text = Component.literal("  " + line.kind().getDescription().getString() + ": ")
                    .append(RanchStatus.text(line, now)).withStyle(color);
            source.sendSuccess(() -> text, false);
        }
        if (status.products().collectingOff()) {
            send(source, "  collecting: switched off for every rancher", ChatFormatting.RED);
        }
        if (status.products().noShears()) {
            send(source, "  wool: woolly sheep wait, but no collecting rancher has shears (never fetched)", ChatFormatting.RED);
        }

        // Strays and loose animals, every Rancher of the village asked: the same words as the Ranch window.
        RanchOutside.Result outside = RanchOutside.compute(level, ctx.mgr(), ranch,
                rancher -> GetToolActivity.canAcquire(rancher, LeadSupply.REQUIREMENT));
        if (outside.listed().isEmpty()) {
            send(source, "  no strays, and no loose ranch animals within " + RanchRoundUp.MAX_RANGE + " blocks",
                    ChatFormatting.GRAY);
            return;
        }
        for (RanchOutside.Outsider out : outside.listed()) {
            String name = EntityType.byString(out.type()).map(type -> type.getDescription().getString()).orElse(out.type());
            String what = String.format("  %s %s at %d %d %d, %d from the gate: ",
                    out.stray() ? (out.letGo() ? "stray (let go)" : "stray") : "loose", name,
                    (int) Math.floor(out.x()), (int) Math.floor(out.y()), (int) Math.floor(out.z()), out.distance());
            ChatFormatting color = switch (RanchRoundUp.tone(out.why().reason())) {
                case GOOD -> ChatFormatting.GREEN;
                case WAIT -> ChatFormatting.YELLOW;
                case BLOCKED -> ChatFormatting.RED;
            };
            Component text = Component.literal(what).append(RanchRoundUp.text(out.why())).withStyle(color);
            source.sendSuccess(() -> text, false);
        }
        if (outside.more() > 0) send(source, "  ... and " + outside.more() + " more", ChatFormatting.GRAY);
    }

    /** The rancher standing nearest the player, or null when the village has none loaded. */
    private static VillagerModel nearestRancher(VillageManager village, Vec3 here) {
        VillagerModel best = null;
        double bestDist = Double.MAX_VALUE;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || npc.getProfession() != Profession.RANCHER) continue;
            double dist = npc.position().distanceToSqr(here);
            if (dist < bestDist) {
                bestDist = dist;
                best = model;
            }
        }
        return best;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static void send(CommandSourceStack source, String text, ChatFormatting style) {
        source.sendSuccess(() -> Component.literal(text).withStyle(style), false);
    }
}
