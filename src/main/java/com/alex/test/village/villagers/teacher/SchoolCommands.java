package com.alex.test.village.villagers.teacher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.teacher.behavior.TeacherActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * {@code /school}: for trying the school out.
 *
 * <pre>
 *   /school                 every school in this village: teachers, children, what is on now
 *   /school time &lt;part&gt;     jumps the clock forward to: bell, lesson1, recess, lesson2, lunch,
 *                           lesson3, reading, home (tomorrow's, if today's has passed)
 *   /school grow            the nearest child (8 blocks) grows up now
 * </pre>
 */
public final class SchoolCommands {

    private SchoolCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("school")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("time")
                        .then(Commands.argument("part", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (String s : List.of("bell", "lesson1", "recess", "lesson2", "lunch",
                                            "lesson3", "reading", "home")) b.suggest(s);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> jump(ctx.getSource(), StringArgumentType.getString(ctx, "part")))))
                .then(Commands.literal("grow").executes(ctx -> grow(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, net.minecraft.core.BlockPos.containing(source.getPosition()));
        if (village == null) {
            source.sendFailure(Component.literal("Not in a village."));
            return 0;
        }
        int schools = 0;
        for (Building b : village.getBuildings()) {
            if (!(b instanceof SchoolBuilding school)) continue;
            schools++;
            StringBuilder text = new StringBuilder("School at " + school.getDoorPos().toShortString()
                    + (school.isValid() ? "" : " (not valid)"));
            for (String row : SchoolReport.rows(level, village, school)) {
                text.append("\n  ").append(row.replace("\t", ": "));
            }
            String line = text.toString();
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        }
        if (schools == 0) source.sendSuccess(() -> Component.literal("This village has no School."), false);
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain().profession() != Profession.TEACHER) continue;
            NpcEntity npc = model.puppet();
            String line;
            if (npc == null) {
                line = VillageManager.displayName(model) + " (unloaded)";
            } else {
                // Asks fresh, the same question the brain asks: also takes a lectern if it has none.
                String why = TeacherActivity.noWorkReason(
                        new Activity.Context(model.brain(), model, village, level, npc));
                SchoolClasses.ClassKey key = SchoolClasses.savedTeacherClass(npc);
                line = VillageManager.displayName(model) + " at " + npc.blockPosition().toShortString()
                        + ": lectern " + (key != null ? key.lectern().toShortString() : "none")
                        + ", " + (why == null ? "has work" : "no work: " + why);
            }
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
        }
        String clock = SchoolDay.clock((int) Math.floorMod(level.getDayTime(), 24000L));
        source.sendSuccess(() -> Component.literal("Time " + clock + ", "
                + SchoolDay.phase(level.getDayTime(), true).name().toLowerCase(Locale.ROOT)
                + ", next bell " + SchoolDay.nextBell(level.getDayTime(), true)), false);
        return schools;
    }

    private static int jump(CommandSourceStack source, String part) {
        int target = switch (part.toLowerCase(Locale.ROOT)) {
            case "bell" -> SchoolDay.FIRST_BELL;
            case "lesson1" -> SchoolDay.LESSON_1_START;
            case "recess" -> SchoolDay.RECESS_START;
            case "lesson2" -> SchoolDay.LESSON_2_START;
            case "lunch" -> SchoolDay.LUNCH_START;
            case "lesson3" -> SchoolDay.LESSON_3_START;
            case "reading" -> SchoolDay.READING_START;
            case "home" -> SchoolDay.HOME_BELL;
            default -> -1;
        };
        if (target < 0) {
            source.sendFailure(Component.literal("Unknown part of the day: " + part));
            return 0;
        }
        ServerLevel level = source.getLevel();
        long now = level.getDayTime();
        long dayStart = now - Math.floorMod(now, 24000L);
        long to = dayStart + target - 20;
        if (to <= now) to += 24000L;
        for (ServerLevel l : level.getServer().getAllLevels()) l.setDayTime(to);
        String clock = SchoolDay.clock(target);
        source.sendSuccess(() -> Component.literal("Clock moved forward to just before " + clock + "."), true);
        return 1;
    }

    private static int grow(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        NpcEntity child = null;
        double best = 64.0;
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(8))) {
            if (npc.getProfession() != Profession.CHILD) continue;
            double d = npc.position().distanceToSqr(at);
            if (d < best) {
                best = d;
                child = npc;
            }
        }
        if (child == null) {
            source.sendFailure(Component.literal("No child within 8 blocks."));
            return 0;
        }
        child.getBrainData().putLong("ChildBornVillageTime", Long.MIN_VALUE / 4);
        String name = child.givenName() != null ? child.givenName() : "The child";
        source.sendSuccess(() -> Component.literal(name + " grows up on its next tick."), false);
        return 1;
    }
}
