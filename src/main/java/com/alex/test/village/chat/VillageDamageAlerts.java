package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.network.VillageVillagerAlertPayload;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.log.VillageLog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tells players inside a town what is happening to its villagers, each as
 * much as their own {@link TownAlertLevel} asks for. A fight is shown by the
 * fight bar ({@link TownFightBar}); chat gets one line when it starts, deaths,
 * and one line when it is over. "All" still gets every hit and sighting.
 *
 * <p>Every line is one short line on a small screen: a coloured mark, the
 * villager's name, what happened, and where.
 */
public final class VillageDamageAlerts {

    /** One villager's hits, one monster's sightings: one line per this long ("All"). */
    private static final long CHAT_COOLDOWN_TICKS = 200L;
    /** The same hazard anywhere in a village: one line per minute ("Normal")... */
    private static final long VILLAGE_HAZARD_COOLDOWN_TICKS = 1200L;
    /**
     * ...and after two lines in a row, one per five minutes while it keeps
     * happening: a villager stuck in a wall or starving used to drip a line
     * into chat every minute for as long as it lasted.
     */
    private static final long LASTING_HAZARD_COOLDOWN_TICKS = 6000L;
    /** Lines after which a hazard counts as lasting. */
    private static final int LINES_BEFORE_LONG_WAIT = 2;
    /**
     * The "every hit" switches: one line per villager per cause a second.
     * A villager in a cactus is hurt twice a second.
     */
    private static final long EVERY_HIT_COOLDOWN_TICKS = 20L;
    /** A wait is forgotten this long after its last hit, so a hazard that stops and comes back starts afresh. */
    private static final long FORGET_TICKS = LASTING_HAZARD_COOLDOWN_TICKS;
    private static final int OUTLINE_DURATION_TICKS = 20 * 30;
    private static final Map<AlertKey, AlertTimes> LAST_ALERTS = new HashMap<>();

    private static final String MARK_ATTACK = "⚔";
    private static final String MARK_WARNING = "⚠";
    private static final String MARK_DEATH = "☠";
    private static final String MARK_HELP = "✚";

    private VillageDamageAlerts() {}

    /**
     * A villager was hurt: "Bram walked into a cactus (12 64 -200)".
     * <ul>
     * <li>A hostile monster's hit is red and part of a fight. "Normal" hears
     *     only the fight's first one, the fight bar shows the rest; "All"
     *     hears one per villager per 10 s.</li>
     * <li>A player's hit is red and has its own wait for each player, so it is
     *     never hidden behind a zombie's; "Normal" and "All" hear it.</li>
     * <li>Any other attacker (a wolf, a bee, a dispenser, someone's TNT) is
     *     red but no fight: nothing for the bar to count, and no "Fight over"
     *     after it.</li>
     * <li>Anything else the world does to them is yellow.</li>
     * </ul>
     * Other attackers and the world: "All" hears them once per villager per
     * cause per 10 s, "Normal" once per cause per minute for the whole
     * village, then once per five minutes if it goes on.
     * A player's own "every villager injury" switch skips those waits: every
     * injury not from a monster, a player's hits included, at most once a
     * second per villager and cause, at any level but Off.
     *
     * @param byMonster a hostile mob, or its arrow or blast, did it
     */
    public static void announceDamage(VillageManager manager, ServerLevel level, NpcEntity villager,
                                      DamageCause cause, boolean byMonster) {
        long now = level.getGameTime();
        pruneAlerts(now);
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        boolean playerHit = cause.attack() && cause.player() != null;
        boolean monsterHit = cause.attack() && !playerHit && byMonster;
        boolean fightStart = monsterHit && TownFightBar.noteAttack(manager, villager, anyoneHearsImportant(audience));
        if (audience.isEmpty()) return;

        AlertKey villagerKey = cause.attack()
                ? new AlertKey(manager.getVillageId(), villager.getNpcId(), AlertReason.ATTACK,
                        playerHit ? cause.player().toString() : "")
                : new AlertKey(manager.getVillageId(), villager.getNpcId(), AlertReason.HURT, cause.alertGroup());
        boolean villagerDue = due(villagerKey, now, CHAT_COOLDOWN_TICKS);
        boolean villageDue = !playerHit && !monsterHit && dueBackingOff(new AlertKey(manager.getVillageId(),
                manager.getVillageId(), AlertReason.HAZARD, cause.alertGroup()), now);
        // The "every villager injury" switch: a line a second at most for this villager and cause.
        // Its wait only runs while someone has the switch on.
        boolean everyDue = !monsterHit && anyoneWantsEvery(audience)
                && due(new AlertKey(manager.getVillageId(), villager.getNpcId(), AlertReason.EVERY,
                        playerHit ? cause.player().toString() : cause.alertGroup()), now, EVERY_HIT_COOLDOWN_TICKS);

        Predicate<TownAlertLevel> byLevel;
        if (playerHit) {
            byLevel = alerts -> villagerDue && alerts.hearsImportant();
        } else if (monsterHit) {
            byLevel = alerts -> alerts.hearsEverything() ? villagerDue || fightStart
                    : fightStart && alerts.hearsImportant();
        } else {
            byLevel = alerts -> alerts.hearsEverything() ? villagerDue : villageDue && alerts.hearsImportant();
        }
        Predicate<ServerPlayer> hears = player -> {
            TownAlertLevel alerts = TownAlertPrefs.of(player);
            return byLevel.test(alerts) || everyDue && alerts != TownAlertLevel.OFF
                    && TownAlertPrefs.everyInjury(player);
        };
        BlockPos at = cause.hazard() != null ? cause.hazard() : villager.blockPosition();
        sendTo(audience, hears, () -> line(level, cause.attack() ? MARK_ATTACK : MARK_WARNING,
                cause.attack() ? ChatFormatting.RED : ChatFormatting.YELLOW,
                gray(cause.sentence().about(who(villager))), at));
        outline(audience, villager, !cause.attack() ? Outline.YELLOW
                : villager.getProfession().isDefender() ? Outline.BLUE : Outline.RED);
    }

    /**
     * A villager died, in vanilla's own words ("Bram was slain by Zombie").
     * No wait: a death is never held back behind the hit before it. Everyone
     * hears it but those with alerts Off.
     *
     * Also written in the village's log (Mayor > Village Log), first, so a death with
     * no player in town is still kept.
     *
     * @param byMonster a monster killed it: a death in the fight, which counts
     *                  it as hurt too, and is its opening if nothing was yet
     */
    public static void announceDeath(VillageManager manager, ServerLevel level, NpcEntity villager,
                                     Component deathMessage, boolean byMonster) {
        // Command and film puppets are not people: 200 of them would push the real deaths out of the log.
        if (!villager.isPreviewPuppet() && !villager.isNoAi()) {
            VillageLog.record(manager, level, VillageLog.Kind.DEATH,
                    Tr.c("log.dynamicvillages.death", "%s died", jobAndName(villager)),
                    withNameHighlighted(villager, deathMessage), villager.blockPosition());
        }
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (byMonster) TownFightBar.noteMonsterKill(manager, villager, anyoneHearsImportant(audience));
        if (audience.isEmpty()) return;
        send(audience, TownAlertLevel::hearsDeaths, () -> line(level, MARK_DEATH, ChatFormatting.RED,
                withNameHighlighted(villager, deathMessage).copy().withStyle(ChatFormatting.GRAY),
                villager.blockPosition()));
    }

    /**
     * A villager spotted a monster. Only "All" hears it, once per monster per
     * 10 s however many villagers see it; the fight bar counts it for everyone
     * else. Each villager that sees it still gets the pulsing red outline.
     */
    public static void announceSighting(
            VillageManager manager, ServerLevel level, NpcEntity villager, LivingEntity monster) {
        long now = level.getGameTime();
        pruneAlerts(now);
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (audience.isEmpty()) return;
        boolean due = due(new AlertKey(manager.getVillageId(), monster.getUUID(), AlertReason.SIGHTING, ""),
                now, CHAT_COOLDOWN_TICKS);
        send(audience, alerts -> due && alerts.hearsEverything(), () -> line(level, MARK_WARNING,
                ChatFormatting.RED, gray(Tr.c("chat.dynamicvillages.alert.spotted", "%1$s spotted %2$s",
                        who(villager), DamageCause.nameOf(monster))), villager.blockPosition()));
        outline(audience, villager, Outline.RED);
    }

    /**
     * A lookout rang its watchtower's bell. Ringing one yourself tells you on
     * the action bar; the lookout's ring was silent in chat, so players only
     * heard a bell somewhere and saw everybody run indoors. The lookout gets the
     * steady green outline, not the red "under attack" one: nothing hit it.
     * What the bell does is in the line's hover text.
     */
    public static void announceTowerBell(
            VillageManager manager, ServerLevel level, NpcEntity lookout, boolean raid) {
        long now = level.getGameTime();
        pruneAlerts(now);
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (audience.isEmpty()) return;
        boolean due = due(new AlertKey(manager.getVillageId(), lookout.getNpcId(), AlertReason.BELL, ""),
                now, CHAT_COOLDOWN_TICKS);
        send(audience, alerts -> due && alerts.hearsImportant(), () -> {
            MutableComponent rang = raid
                    ? Tr.c("chat.dynamicvillages.alert.tower_bell_raid", "%s rang the tower bell: a raid!", who(lookout))
                    : Tr.c("chat.dynamicvillages.alert.tower_bell", "%s rang the tower bell", who(lookout));
            MutableComponent text = line(level, MARK_WARNING, ChatFormatting.RED, gray(rang), lookout.blockPosition());
            return text.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    alarmHover())));
        });
        outline(audience, lookout, Outline.GREEN);
    }

    /**
     * A player the owner listed as Hostile came inside the village (village claims round 11a): "Bob (hostile)
     * entered Anna's village". Everyone in town hears it, and the owner and Officers wherever they are; never
     * the Hostile player. {@code HostileWatch} spaces the lines out.
     */
    public static void announceHostileEntered(VillageManager manager, ServerLevel level, ServerPlayer hostile) {
        List<ServerPlayer> audience = new ArrayList<>(playersInsideTown(manager, level));
        com.alex.test.village.claims.VillageClaim claim = manager.claim();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            com.alex.test.village.claims.ClaimRank rank = claim.rankOf(player.getUUID());
            boolean runsIt = rank == com.alex.test.village.claims.ClaimRank.OWNER
                    || rank == com.alex.test.village.claims.ClaimRank.OFFICER;
            if (runsIt && !audience.contains(player)) audience.add(player);
        }
        audience.remove(hostile);
        if (audience.isEmpty()) return;
        send(audience, alerts -> alerts.hearsImportant(), () -> line(level, MARK_WARNING, ChatFormatting.RED,
                gray(Tr.c("chat.dynamicvillages.alert.hostile_entered", "%1$s (hostile) entered %2$s's village",
                        Component.literal(hostile.getGameProfile().getName()).withStyle(ChatFormatting.YELLOW),
                        claim.ownerName())), hostile.blockPosition()));
    }

    /** A guard ran to the village bell and rang it for a raid nobody had warned the village of. */
    public static void announceRaidBell(VillageManager manager, ServerLevel level, NpcEntity guard) {
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (audience.isEmpty()) return;
        send(audience, alerts -> alerts.hearsImportant(), () -> {
            MutableComponent text = line(level, MARK_WARNING, ChatFormatting.RED, gray(Tr.c(
                    "chat.dynamicvillages.alert.raid_bell", "%s rang the village bell: raiders!", who(guard))),
                    guard.blockPosition());
            return text.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    alarmHover())));
        });
        outline(audience, guard, Outline.GREEN);
    }

    /**
     * Whether players in town were told {@code npcId} was hurt within the last
     * {@code withinTicks}: the cleric then says it is on its way.
     */
    public static boolean recentlyAlertedHurt(VillageManager manager, UUID npcId, long now, long withinTicks) {
        if (npcId == null) return false;
        for (Map.Entry<AlertKey, AlertTimes> entry : LAST_ALERTS.entrySet()) {
            AlertKey key = entry.getKey();
            if (!key.villageId().equals(manager.getVillageId()) || !npcId.equals(key.subjectId())) continue;
            if (key.reason() != AlertReason.ATTACK && key.reason() != AlertReason.HURT) continue;
            long chat = entry.getValue().lastChatTick;
            if (chat != Long.MIN_VALUE && now - chat >= 0 && now - chat <= withinTicks) return true;
        }
        return false;
    }

    /**
     * A cleric has set off to heal a villager the town was just told about:
     * "Ana is on the way to heal Bram". Only "All" hears it; the fight's
     * closing line counts it for "Normal". The cleric gets the steady green
     * outline, so players can see who is coming.
     */
    public static void announceHelpComing(VillageManager manager, ServerLevel level, NpcEntity cleric,
                                          NpcEntity patient) {
        TownFightBar.noteHelp(manager, patient);
        long now = level.getGameTime();
        pruneAlerts(now);
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (audience.isEmpty()) return;
        boolean due = due(new AlertKey(manager.getVillageId(), patient.getNpcId(), AlertReason.HELP, ""),
                now, CHAT_COOLDOWN_TICKS);
        send(audience, alerts -> due && alerts.hearsEverything(), () -> line(level, MARK_HELP,
                ChatFormatting.GREEN, gray(Tr.c("chat.dynamicvillages.alert.help_coming", "%1$s is on the way to heal %2$s",
                        who(cleric), who(patient))),
                patient.blockPosition()));
        outline(audience, cleric, Outline.GREEN);
    }

    /** A cleric's Guardian Angel kept a villager from dying: "Ana's prayer saved Bram". */
    public static void announceGuardianAngel(VillageManager manager, ServerLevel level, NpcEntity cleric,
                                             NpcEntity saved) {
        List<ServerPlayer> audience = playersInsideTown(manager, level);
        if (audience.isEmpty()) return;
        send(audience, TownAlertLevel::hearsImportant, () -> line(level, MARK_HELP, ChatFormatting.GREEN,
                gray(Tr.c("chat.dynamicvillages.alert.guardian_angel", "%1$s's prayer saved %2$s from death",
                        who(cleric), who(saved))),
                saved.blockPosition()));
    }

    /** Whether the line under {@code key} is due, and if so starts its wait again. */
    private static boolean due(AlertKey key, long now, long cooldownTicks) {
        AlertTimes times = LAST_ALERTS.computeIfAbsent(key, ignored -> new AlertTimes());
        times.lastSeenTick = now;
        if (times.lastChatTick != Long.MIN_VALUE && now - times.lastChatTick < cooldownTicks) return false;
        times.lastChatTick = now;
        return true;
    }

    /**
     * {@link #due}, for a hazard told village-wide: one line a minute, and
     * after {@link #LINES_BEFORE_LONG_WAIT} of them one per five minutes,
     * until it has stopped for {@link #FORGET_TICKS}.
     */
    private static boolean dueBackingOff(AlertKey key, long now) {
        AlertTimes times = LAST_ALERTS.get(key);
        long wait = times != null && times.lines >= LINES_BEFORE_LONG_WAIT
                ? LASTING_HAZARD_COOLDOWN_TICKS : VILLAGE_HAZARD_COOLDOWN_TICKS;
        if (!due(key, now, wait)) return false;
        LAST_ALERTS.get(key).lines++;
        return true;
    }

    private static void pruneAlerts(long now) {
        // A time behind the last alert means the world was reloaded from an
        // earlier save; its old waits would otherwise hold chat back for as
        // long as the clock took to catch up.
        LAST_ALERTS.entrySet().removeIf(entry -> now < entry.getValue().lastSeenTick
                || now - entry.getValue().lastSeenTick > FORGET_TICKS);
    }

    /** Whether someone in town would hear a fight's opening and closing lines. */
    private static boolean anyoneHearsImportant(List<ServerPlayer> audience) {
        for (ServerPlayer player : audience) {
            if (TownAlertPrefs.of(player).hearsImportant()) return true;
        }
        return false;
    }

    /** Whether someone in town has "every villager injury" on, alerts not Off. */
    private static boolean anyoneWantsEvery(List<ServerPlayer> audience) {
        for (ServerPlayer player : audience) {
            if (TownAlertPrefs.everyInjury(player) && TownAlertPrefs.of(player) != TownAlertLevel.OFF) return true;
        }
        return false;
    }

    /** Sends the line to each player whose alert level wants it; built only if someone does. */
    private static void send(List<ServerPlayer> audience, Predicate<TownAlertLevel> hears,
                             Supplier<Component> message) {
        sendTo(audience, player -> hears.test(TownAlertPrefs.of(player)), message);
    }

    /** {@link #send}, for a line that also depends on the player's own switches. */
    private static void sendTo(List<ServerPlayer> audience, Predicate<ServerPlayer> hears,
                               Supplier<Component> message) {
        Component text = null;
        for (ServerPlayer player : audience) {
            if (!hears.test(player)) continue;
            if (text == null) text = message.get();
            player.sendSystemMessage(text);
        }
    }

    /** The glowing outline on the villager, for everyone in town but those with alerts Off. */
    private static void outline(List<ServerPlayer> audience, NpcEntity villager, Outline outline) {
        VillageVillagerAlertPayload payload = new VillageVillagerAlertPayload(villager.getUUID(),
                OUTLINE_DURATION_TICKS, outline != Outline.GREEN, outline == Outline.YELLOW, outline == Outline.BLUE);
        for (ServerPlayer player : audience) {
            if (TownAlertPrefs.of(player).seesFight()) PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** "Farmer Bram", or just "Bram" without a job: how the alerts and the village log name a villager. */
    public static MutableComponent jobAndName(NpcEntity villager) {
        return withJob(villager, villager.getDisplayName());
    }

    /** "Farmer Bram" in yellow, for the sentences below. */
    private static MutableComponent who(NpcEntity villager) {
        return withJob(villager, villager.getDisplayName()).withStyle(ChatFormatting.YELLOW);
    }

    /** What happened, in grey; the names in it keep their yellow. */
    private static MutableComponent gray(MutableComponent sentence) {
        return sentence.withStyle(ChatFormatting.GRAY);
    }

    /** The hover text on a bell's line: what the bell does. */
    private static Component alarmHover() {
        return Tr.c("chat.dynamicvillages.alert.alarm_hover", "Guards are on alert and villagers are heading indoors.");
    }

    /**
     * "Guard Gareth": players could not tell a guard taking blows in a fight
     * from a farmer being chased. Jobless villagers keep just their name.
     */
    private static MutableComponent withJob(NpcEntity villager, Component name) {
        com.alex.test.village.villagers.core.Profession job = villager.getProfession();
        if (job == null || job == com.alex.test.village.villagers.core.Profession.NONE) return name.copy();
        // A villager from before given names is just "Villager": "Farmer", not "Farmer Villager".
        Component jobName = Component.translatableWithFallback(job.translationKey(), job.displayName());
        if ("Villager".equals(name.getString())) return jobName.copy();
        return Tr.c("chat.dynamicvillages.alert.job_and_name", "%1$s %2$s", jobName, name);
    }

    /** "⚔ Bram is being attacked by a Zombie (12 64 -200)": mark, what happened, where. */
    private static MutableComponent line(ServerLevel level, String mark, ChatFormatting markColor,
                                         Component body, BlockPos pos) {
        MutableComponent message = Component.literal(mark + " ").withStyle(markColor);
        Component where = Component.literal("(" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")")
                .withStyle(ChatFormatting.AQUA);
        String dimension = level.dimension().location().toString();
        if ("minecraft:overworld".equals(dimension)) {
            return message.append(body).append(" ").append(where);
        }
        return message.append(Tr.c("chat.dynamicvillages.alert.line_in_dimension", "%1$s %2$s in %3$s",
                body, where, Component.literal(dimension)).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Vanilla death messages put the one who died first ("%1$s was slain by
     * %2$s"); that name gets its job and is made yellow like the villager's
     * name in every other alert ("Guard Gareth was slain by Zombie"). Anything
     * not built that way is left as it is.
     */
    private static Component withNameHighlighted(NpcEntity villager, Component deathMessage) {
        if (!(deathMessage.getContents() instanceof TranslatableContents translatable)) return deathMessage;
        Object[] args = translatable.getArgs().clone();
        if (args.length == 0 || !(args[0] instanceof Component name)) return deathMessage;
        args[0] = withJob(villager, name).withStyle(ChatFormatting.YELLOW);
        return Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), args)
                .withStyle(deathMessage.getStyle());
    }

    /** Players standing inside this town (not a neighbouring one), in its level. */
    static List<ServerPlayer> playersInsideTown(VillageManager manager, ServerLevel level) {
        if (manager.getLevel() != level) return List.of();

        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        List<ServerPlayer> audience = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            BlockPos playerPos = player.blockPosition();
            if (!manager.contains(playerPos)) continue;

            VillageManager playerTown = registry.findVillageAt(level, playerPos);
            boolean inThisTown = playerTown == manager
                    || (playerTown == null
                    && manager == VillageManager.INSTANCE);
            if (inThisTown) audience.add(player);
        }
        return audience;
    }

    private static final class AlertTimes {
        private long lastChatTick = Long.MIN_VALUE;
        private long lastSeenTick;
        /** Lines sent since it was last forgotten (village-wide hazards only). */
        private int lines;
    }

    private enum AlertReason { ATTACK, HURT, HAZARD, SIGHTING, BELL, HELP, EVERY }

    /**
     * Red pulses for danger, yellow pulses for the world hurting them, green
     * is steady: "here they are". Blue is a guard, captain or archer in a
     * fight: steady, flashing when it is low on health (decided by the client).
     */
    private enum Outline { RED, BLUE, YELLOW, GREEN }

    /**
     * {@code subjectId} is the affected villager, the spotted monster, or the
     * village itself for a HAZARD (the village-wide wait); {@code cause} is
     * the damage group for HURT, HAZARD and EVERY, so a cactus does not hold back a
     * drowning message, the player's id for a player's ATTACK or EVERY, and empty
     * otherwise.
     */
    private record AlertKey(UUID villageId, UUID subjectId, AlertReason reason, String cause) {}
}
