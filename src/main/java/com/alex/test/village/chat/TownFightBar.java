package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fight bar: while monsters are in a village, players inside it see a bar
 * at the top of the screen, like the raid bar, saying how many are left and
 * how many villagers they have hurt. It changes in place, so a big fight no
 * longer fills the chat box; chat gets one line when the first villager is
 * hurt and one when the fight is over (see {@link VillageDamageAlerts}).
 *
 * <p>Counts what the guards' threat list knows ({@link
 * GuardAlertSystem#monstersInTown}), once a second. Hidden from a player
 * standing in a vanilla raid, whose own bar already says it, and from a
 * player whose {@link TownAlertLevel} is Off.
 */
public final class TownFightBar {

    private static final int UPDATE_TICKS = 20;
    /**
     * The bar stays, green, this long after the last monster: a zombie that
     * steps out of sight behind a wall does not end the fight and start a new
     * one, and players see that it is over.
     */
    private static final int ALL_CLEAR_TICKS = 20 * 20;
    /**
     * Monsters nobody has been hurt by are shown this long after the last new
     * one arrived. A zombie stuck in a hole inside the village used to keep
     * the yellow bar up all night; it comes back for a new monster or a hurt
     * villager.
     */
    private static final int QUIET_SHOWN_TICKS = 20 * 120;

    /** Village id -> its fight. */
    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

    private TownFightBar() {}

    private static final class Fight {
        private final ServerBossEvent bar = new ServerBossEvent(Component.empty(),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        /** Every monster counted in this fight. */
        private final Set<UUID> monsters = new HashSet<>();
        private final Set<UUID> beaten = new HashSet<>();
        /** Guards, captains and archers a monster has hurt (npc ids): the guards doing their job. */
        private final Set<UUID> hurtGuards = new HashSet<>();
        /** Everyone else a monster has hurt: the monsters got past the guards. */
        private final Set<UUID> hurtVillagers = new HashSet<>();
        /** Villagers a cleric set off to heal. */
        private final Set<UUID> helped = new HashSet<>();
        private int died;
        private int alive;
        /** Most monsters at once: the bar is full at this many. */
        private int most;
        /** Whether chat was told it started; only then is it told it ended. */
        private boolean announced;
        private long clearSince = Long.MIN_VALUE;
        /** When the last monster not counted before showed up. */
        private long newMonsterAt;
    }

    // -------------------------------------------------------------------------
    // What happens in a fight
    // -------------------------------------------------------------------------

    /**
     * A hostile monster (or its arrow or blast) hurt a villager. Returns true
     * the first time in a fight that somebody in town hears fight lines
     * ({@link TownAlertLevel#hearsImportant}): the fight's one opening line
     * in chat is due. A player on Deaths or Off does not use it up. A guard,
     * captain or archer hit is only counted on the bar: guards taking blows
     * is a fight going as it should, not news for chat.
     */
    static boolean noteAttack(VillageManager manager, NpcEntity villager, boolean someoneHears) {
        Fight fight = FIGHTS.computeIfAbsent(manager.getVillageId(), id -> new Fight());
        if (!countHurt(fight, villager)) return false;
        if (fight.announced || !someoneHears) return false;
        fight.announced = true;
        return true;
    }

    /**
     * A monster killed a villager: hurt and died in the fight, which this
     * starts if a one-hit kill came before any other blow. The death line is
     * the fight's opening when there was none, so its end is told too.
     */
    static void noteMonsterKill(VillageManager manager, NpcEntity villager, boolean someoneHears) {
        Fight fight = FIGHTS.computeIfAbsent(manager.getVillageId(), id -> new Fight());
        countHurt(fight, villager);
        fight.died++;
        if (someoneHears) fight.announced = true;
    }

    /**
     * Counts a hurt villager once, as what it was when first hurt in this
     * fight: a farmer handed a guard token mid-fight was counted as both.
     * True when it counts as a villager, not a guard.
     */
    private static boolean countHurt(Fight fight, NpcEntity villager) {
        UUID id = villager.getNpcId();
        if (fight.hurtGuards.contains(id)) return false;
        if (fight.hurtVillagers.contains(id)) return true;
        boolean guard = villager.getProfession().isDefender();
        (guard ? fight.hurtGuards : fight.hurtVillagers).add(id);
        return !guard;
    }

    static void noteHelp(VillageManager manager, NpcEntity patient) {
        Fight fight = FIGHTS.get(manager.getVillageId());
        if (fight != null) fight.helped.add(patient.getNpcId());
    }

    /** A monster this fight counted has died: one beaten, whoever killed it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMonsterDeath(LivingDeathEvent event) {
        if (FIGHTS.isEmpty() || event.getEntity() instanceof NpcEntity) return;
        if (event.getEntity().level().isClientSide()) return;
        UUID id = event.getEntity().getUUID();
        for (Fight fight : FIGHTS.values()) {
            if (fight.monsters.contains(id)) fight.beaten.add(id);
        }
    }

    // -------------------------------------------------------------------------
    // The bar, once a second
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        // Frozen, the game time stands still and this would update every tick.
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
        if (level.getGameTime() % UPDATE_TICKS != 0) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());

        // A village that is gone takes its bar with it.
        Iterator<Map.Entry<UUID, Fight>> it = FIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Fight> entry = it.next();
            if (registry.get(entry.getKey()) == null) {
                entry.getValue().bar.removeAllPlayers();
                it.remove();
            }
        }

        for (VillageManager manager : registry.all()) {
            if (manager.getLevel() == level) tick(manager, level);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (Fight fight : FIGHTS.values()) fight.bar.removePlayer(player);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Fight fight : FIGHTS.values()) fight.bar.removeAllPlayers();
        FIGHTS.clear();
    }

    private static void tick(VillageManager manager, ServerLevel level) {
        UUID villageId = manager.getVillageId();
        Fight fight = FIGHTS.get(villageId);
        List<ServerPlayer> inTown = VillageDamageAlerts.playersInsideTown(manager, level);
        // Nobody to show a new fight to: do not count one.
        if (fight == null && inTown.isEmpty()) return;

        List<LivingEntity> monsters = GuardAlertSystem.monstersInTown(manager, level);
        if (fight == null) {
            if (monsters.isEmpty()) return;
            fight = new Fight();
            FIGHTS.put(villageId, fight);
        }

        long now = level.getGameTime();
        for (LivingEntity monster : monsters) {
            if (fight.monsters.add(monster.getUUID())) fight.newMonsterAt = now;
        }
        fight.alive = monsters.size();
        fight.most = Math.max(fight.most, fight.alive);
        if (fight.alive > 0) {
            fight.clearSince = Long.MIN_VALUE;
        } else if (fight.clearSince == Long.MIN_VALUE) {
            fight.clearSince = now;
        } else if (now - fight.clearSince >= ALL_CLEAR_TICKS) {
            end(manager, level, fight, inTown);
            FIGHTS.remove(villageId);
            return;
        }

        updateBar(fight, now);
        // The raid bar says it during a raid: one bar, not two.
        updatePlayers(level, fight, manager.raids().barShowing() ? List.of() : inTown);
    }

    private static void updateBar(Fight fight, long now) {
        // Somebody was hurt: a real fight, not monsters walking past.
        boolean attack = !fight.hurtGuards.isEmpty() || !fight.hurtVillagers.isEmpty() || fight.died > 0;
        Component title;
        BossEvent.BossBarColor color;
        // A blast with no monster the village knew of (a creeper) is noted, but there is nothing to count.
        boolean visible = !fight.monsters.isEmpty();
        int alive = fight.alive;
        if (alive == 0) {
            title = marked("✔ ", Tr.c("chat.dynamicvillages.fight.clear", "The village is clear"));
            color = BossEvent.BossBarColor.GREEN;
            // Only a real fight ends on the green bar; a zombie that walked past just goes.
            visible &= attack;
        } else if (!attack) {
            visible &= now - fight.newMonsterAt < QUIET_SHOWN_TICKS;
            title = marked("⚠ ", alive == 1
                    ? Tr.c("chat.dynamicvillages.fight.quiet_one", "%s monster in the village", alive)
                    : Tr.c("chat.dynamicvillages.fight.quiet", "%s monsters in the village", alive));
            color = BossEvent.BossBarColor.YELLOW;
        } else if (fight.hurtVillagers.isEmpty()) {
            // Only guards hurt: the guards are handling it. Blue, not red.
            List<Component> facts = new ArrayList<>();
            facts.add(alive == 1
                    ? Tr.c("chat.dynamicvillages.fight.guards_fighting_one", "Guards fighting %s monster", alive)
                    : Tr.c("chat.dynamicvillages.fight.guards_fighting", "Guards fighting %s monsters", alive));
            addHurtAndDied(fight, facts);
            title = marked("⚔ ", joined(facts));
            color = BossEvent.BossBarColor.BLUE;
        } else {
            // A villager who is not a guard got hurt: the monsters got past. Stays red to the end.
            List<Component> facts = new ArrayList<>();
            facts.add(Tr.c("chat.dynamicvillages.fight.under_attack", "Villagers under attack"));
            facts.add(alive == 1
                    ? Tr.c("chat.dynamicvillages.fight.monsters_one", "%s monster", alive)
                    : Tr.c("chat.dynamicvillages.fight.monsters", "%s monsters", alive));
            addHurtAndDied(fight, facts);
            title = marked("⚠ ", joined(facts));
            color = BossEvent.BossBarColor.RED;
        }
        // ServerBossEvent sends only what changed.
        fight.bar.setName(title);
        fight.bar.setColor(color);
        fight.bar.setProgress(fight.most <= 0 ? 0.0F : Math.min(1.0F, fight.alive / (float) fight.most));
        fight.bar.setVisible(visible);
    }

    /** Players in town see it; not with alerts Off, and not inside a vanilla raid. */
    private static void updatePlayers(ServerLevel level, Fight fight, List<ServerPlayer> inTown) {
        Set<ServerPlayer> wanted = new HashSet<>();
        for (ServerPlayer player : inTown) {
            if (!TownAlertPrefs.of(player).seesFight()) continue;
            if (level.getRaidAt(player.blockPosition()) != null) continue;
            wanted.add(player);
        }
        for (ServerPlayer player : new ArrayList<>(fight.bar.getPlayers())) {
            if (!wanted.contains(player)) fight.bar.removePlayer(player);
        }
        for (ServerPlayer player : wanted) fight.bar.addPlayer(player);
    }

    /** "2 guards hurt", "1 villager hurt", "1 died": each side only if somebody on it was. */
    private static void addHurtAndDied(Fight fight, List<Component> facts) {
        addHurt(fight, facts);
        if (fight.died > 0) facts.add(died(fight.died));
    }

    private static void addHurt(Fight fight, List<Component> facts) {
        int guards = fight.hurtGuards.size();
        if (guards == 1) facts.add(Tr.c("chat.dynamicvillages.fight.guards_hurt_one", "%s guard hurt", guards));
        else if (guards > 1) facts.add(Tr.c("chat.dynamicvillages.fight.guards_hurt", "%s guards hurt", guards));
        int villagers = fight.hurtVillagers.size();
        if (villagers == 1) {
            facts.add(Tr.c("chat.dynamicvillages.fight.villagers_hurt_one", "%s villager hurt", villagers));
        } else if (villagers > 1) {
            facts.add(Tr.c("chat.dynamicvillages.fight.villagers_hurt", "%s villagers hurt", villagers));
        }
    }

    private static Component died(int died) {
        return died == 1
                ? Tr.c("chat.dynamicvillages.fight.died_one", "%s died", died)
                : Tr.c("chat.dynamicvillages.fight.died", "%s died", died);
    }

    /** Facts, each a whole phrase of its own, shown side by side: "3 monsters · 1 villager hurt". */
    private static MutableComponent joined(List<Component> facts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) out.append(" · ");
            out.append(facts.get(i));
        }
        return out;
    }

    /** The bar's mark ("⚠ ") in front of its text; marks are not translated. */
    private static Component marked(String mark, Component text) {
        return Component.literal(mark).append(text);
    }

    /**
     * "✔ Fight over: 5 monsters beaten · 2 guards hurt · 1 villager hurt · nobody died",
     * if chat was told it started: a villager was hurt or somebody died. A
     * fight only guards were hurt in stays on the bar.
     */
    private static void end(VillageManager manager, ServerLevel level, Fight fight, List<ServerPlayer> inTown) {
        fight.bar.removeAllPlayers();
        if (!fight.announced || inTown.isEmpty()) return;
        List<Component> facts = new ArrayList<>();
        int beaten = fight.beaten.size();
        if (beaten == 1) facts.add(Tr.c("chat.dynamicvillages.fight.beaten_one", "%s monster beaten", beaten));
        else if (beaten > 1) facts.add(Tr.c("chat.dynamicvillages.fight.beaten", "%s monsters beaten", beaten));
        addHurt(fight, facts);
        facts.add(fight.died == 0 ? Tr.c("chat.dynamicvillages.fight.nobody_died", "nobody died") : died(fight.died));
        int helped = fight.helped.size();
        if (helped == 1) {
            facts.add(Tr.c("chat.dynamicvillages.fight.clerics_helped_one", "clerics helped %s villager", helped));
        } else if (helped > 1) {
            facts.add(Tr.c("chat.dynamicvillages.fight.clerics_helped", "clerics helped %s villagers", helped));
        }
        Component text = Component.literal("✔ ").withStyle(ChatFormatting.GREEN)
                .append(Tr.c("chat.dynamicvillages.fight.over", "Fight over: %s", joined(facts))
                        .withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : inTown) {
            if (TownAlertPrefs.of(player).hearsImportant()) player.sendSystemMessage(text);
        }
    }
}
