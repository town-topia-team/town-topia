package com.alex.test.testkit;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.economy.ReconciliationSummary;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.Personality;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.ThoughtState;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.stats.VillagerStats;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Serialises live village and villager state to JSON.
 *
 * <p>This is the read side of the test loop. Log scraping is too brittle to
 * assert on, so every question a test wants to ask — "is the lumberjack
 * working?", "did hunger drop?", "is the storage room valid?" — is answered
 * from a structured snapshot instead.
 *
 * <p>Snapshots are written to {@code vtest-out/} rather than returned over RCON,
 * which truncates long replies.
 */
public final class StateDump {

    private StateDump() {}

    // =========================================================================
    // Top level
    // =========================================================================

    /** Full snapshot: clock, every village, every villager. */
    public static JsonObject full(MinecraftServer server) {
        JsonObject root = new JsonObject();
        root.add("server", server(server));

        JsonArray villages = new JsonArray();
        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        for (VillageManager mgr : registry.all()) {
            villages.add(village(mgr, registry));
        }
        root.add("villages", villages);
        return root;
    }

    /** Clock and process-level facts a test may need to reason about timing. */
    public static JsonObject server(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        long dayTime = overworld.getDayTime();

        JsonObject json = new JsonObject();
        json.addProperty("gameTime", overworld.getGameTime());
        json.addProperty("dayTime", dayTime);
        json.addProperty("day", dayTime / 24_000L + 1);
        json.addProperty("tickOfDay", dayTime % 24_000L);
        json.addProperty("isNight", VillagerBrain.isNight(overworld));
        json.addProperty("players", server.getPlayerList().getPlayerCount());
        json.addProperty("headless", TestKit.headless());
        return json;
    }

    // =========================================================================
    // Village
    // =========================================================================

    public static JsonObject village(VillageManager mgr, VillageManagerRegistry registry) {
        JsonObject json = new JsonObject();
        UUID id = mgr.getVillageId();
        json.addProperty("id", shortId(id));
        json.addProperty("uuid", id.toString());
        json.addProperty("inactive", registry.isVillageInactive(id));
        json.addProperty("dormant", registry.isVillageDormant(id));
        json.addProperty("reconciling", registry.isVillageReconciling(id));
        long dormantSince = registry.getDormantSinceGameTime(id);
        json.addProperty("dormantSinceGameTime", dormantSince);
        ServerLevel villageLevel = mgr.getLevel();
        // registry.hasDormantSince, not "dormantSince >= 0": the elapse test
        // hook can legitimately rewind a young test world's timestamp below
        // zero, which would otherwise be misread as the "-1 = never dormant"
        // sentinel and under-report a village that is genuinely dormant.
        json.addProperty("dormantElapsedTicks",
                registry.hasDormantSince(id) && villageLevel != null
                        ? villageLevel.getGameTime() - dormantSince
                        : -1L);
        json.add("lastReconciliation", reconciliation(mgr.getLastReconciliation()));
        json.add("bellPos", pos(mgr.getBellPos()));

        NavGraph graph = mgr.getGraph();
        JsonObject graphJson = new JsonObject();
        graphJson.addProperty("built", graph != null && !graph.isEmpty());
        graphJson.addProperty("nodes", graph == null ? 0 : graph.allNodes().size());
        graphJson.addProperty("stats", graph == null ? "none" : graph.summary());   // dumpStats walks every node: 2 s on the beta server
        if (graph != null) {
            graphJson.addProperty("minX", graph.getBoundsMinX());
            graphJson.addProperty("maxX", graph.getBoundsMaxX());
            graphJson.addProperty("minZ", graph.getBoundsMinZ());
            graphJson.addProperty("maxZ", graph.getBoundsMaxZ());
        }
        // How long the last full rebuild took. On a superflat world this is a
        // few milliseconds and nobody cares; on generated terrain the same
        // village rebuilds fifty times as many nodes on the server thread,
        // every time a block changes, so it is worth being able to assert on.
        graphJson.addProperty("lastRebuildMs", mgr.getLastRebuildMillis());
        graphJson.addProperty("lastRebuildLocal", mgr.wasLastRebuildLocal());
        json.add("graph", graphJson);

        JsonArray buildings = new JsonArray();
        for (Building b : mgr.getBuildings()) buildings.add(building(b, mgr.getLevel()));
        json.add("buildings", buildings);

        json.add("knowledge", knowledge(mgr.getKnowledge()));

        JsonArray farms = new JsonArray();
        for (var farm : mgr.farmers().snapshots()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", shortId(farm.id()));
            entry.addProperty("water", farm.waterSources().size());
            entry.addProperty("hydrated", farm.hydrationCoverage().size());
            entry.addProperty("tillable", farm.tillable().size());
            entry.addProperty("tilled", farm.tilled().size());
            entry.addProperty("planted", farm.planted().size());
            entry.addProperty("mature", farm.mature().size());
            entry.addProperty("blocked", farm.blocked().size());
            entry.addProperty("fruitSpaces", farm.fruitSpaces().size());
            entry.addProperty("assignedFarmer",
                    farm.assignedFarmer() == null ? "" : shortId(farm.assignedFarmer()));
            entry.addProperty("activeJob",
                    farm.activeJob() == null ? "" : farm.activeJob().type().name());
            if (farm.activeJob() != null) entry.add("activeTarget", pos(farm.activeJob().target()));
            JsonArray water = new JsonArray();
            for (BlockPos waterPos : farm.waterSources()) water.add(pos(waterPos));
            entry.add("waterSources", water);
            farms.add(entry);
        }
        json.add("farms", farms);

        JsonArray villagers = new JsonArray();
        for (VillagerModel model : mgr.getVillagers()) villagers.add(villager(model));
        json.add("villagers", villagers);

        // Visitors are not village members, so they are reported separately —
        // anything counting population must not accidentally include them.
        ServerLevel level = mgr.getLevel();
        JsonArray visitors = new JsonArray();
        if (level != null) {
            for (com.alex.test.entity.visitor.VisitorEntity v
                    : mgr.visitors().currentVisitors(level)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", shortId(v.getUUID()));
                entry.addProperty("kind", "MERCHANT");
                entry.add("pos", pos(v.blockPosition()));
                entry.addProperty("despawnAt", v.getDespawnAtGameTime());
                entry.addProperty("returningHome", v.isReturningHome());
                entry.add("spawnPos", pos(v.getSpawnOrigin()));
                entry.addProperty("spawnDimension", v.getSpawnDimension().location().toString());
                visitors.add(entry);
            }
        }
        json.add("visitors", visitors);
        com.alex.test.village.visitors.VisitorSpawnManager.SavedState merchantState =
                mgr.visitors().savedState();
        json.addProperty("activeMerchantId", merchantState.activeMerchantId() != null
                ? shortId(merchantState.activeMerchantId()) : "");
        json.addProperty("merchantDepartureAt", merchantState.departureAt());
        json.addProperty("merchantVisitEndedAt", merchantState.visitEndedAt());
        json.addProperty("merchantMorningsSinceVisit", merchantState.morningsSinceVisit());
        json.addProperty("merchantFoundingVisitsLeft",
                merchantState.foundingVisitsLeft());

        JsonArray nomads = new JsonArray();
        if (level != null) {
            for (com.alex.test.entity.visitor.NomadEntity n : mgr.nomads().currentNomads(level)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", shortId(n.getUUID()));
                entry.add("pos", pos(n.blockPosition()));
                entry.add("spawnPos", pos(n.getSpawnOrigin()));
                entry.addProperty("despawnAt", n.getDespawnAtGameTime());
                entry.addProperty("returningHome", n.isReturningHome());
                entry.addProperty("spawnDimension", n.getSpawnDimension().location().toString());
                entry.addProperty("villageMember", n.isVillageMember());
                entry.addProperty("hasVillageLink", n.getVillageLink() != null);
                entry.addProperty("profession", n.getProfession().name());
                nomads.add(entry);
            }
        }
        json.add("nomads", nomads);
        json.addProperty("nomadRollPopulation", mgr.nomads().populationForNextRoll());
        json.addProperty("pendingFoundingNomads", mgr.nomads().pendingWelcomeCount());
        json.addProperty("nomadNextArrivalAt", mgr.nomads().nextPendingArrivalAt());
        json.addProperty("nomadFailedMornings", mgr.nomads().failedMornings());
        json.addProperty("nomadNextChance", mgr.nomads().nextArrivalChance());
        json.addProperty("nomadQuietMorningNext", mgr.nomads().quietMorningNext());
        json.addProperty("nomadFoundingDay", mgr.nomads().foundingDay());
        json.addProperty("merchantPendingArrivalAt", mgr.visitors().pendingArrivalAt());

        return json;
    }

    /**
     * Last away catch-up ({@code economy/away/AwayRun}) for this village, or an
     * explicit "never ran" marker. This is the only external way to verify
     * the offline-economy math actually executed and what it did, since
     * chests/health are the only other observable side effects.
     */
    public static JsonObject reconciliation(@Nullable ReconciliationSummary summary) {
        JsonObject json = new JsonObject();
        if (summary == null) {
            json.addProperty("hasRun", false);
            return json;
        }
        json.addProperty("hasRun", true);
        json.addProperty("ticksReconciled", summary.ticksReconciled());
        json.addProperty("ticksAway", summary.ticksAway());
        json.addProperty("hungryVillagers", summary.hungryVillagers());
        json.addProperty("villagersProcessed", summary.villagersProcessed());
        json.addProperty("hungerRestored", summary.hungerRestored());
        json.addProperty("mealsEaten", summary.mealsEaten());
        json.addProperty("starvationDamageEvents", summary.starvationDamageEvents());
        json.addProperty("birthsSpawned", summary.birthsSpawned());

        JsonObject produced = new JsonObject();
        summary.itemsProduced().forEach(produced::addProperty);
        json.add("itemsProduced", produced);

        JsonObject consumed = new JsonObject();
        summary.itemsConsumed().forEach(consumed::addProperty);
        json.add("itemsConsumed", consumed);

        JsonArray notes = new JsonArray();
        summary.truncationNotes().forEach(notes::add);
        json.add("truncationNotes", notes);
        return json;
    }

    public static JsonObject building(Building b, @Nullable ServerLevel level) {
        JsonObject json = new JsonObject();
        json.addProperty("type", typeName(b));
        json.addProperty("valid", b.isValid());
        if (b instanceof MerchantStallBuilding stall) json.addProperty("openAir", stall.isOpenAir());
        json.add("door", pos(b.getDoorPos()));
        json.add("frame", pos(b.getItemFramePos()));
        json.addProperty("interiorDir", b.getInteriorDirection().getName());
        json.addProperty("roomTiles", b.getNavMeshBlocks().size());
        json.addProperty("functionalBlocks", b.getFunctionalBlocks().size());
        if (b.countsCrowding()) {
            JsonObject space = new JsonObject();
            space.addProperty("space", b.getSpace().space());
            space.addProperty("floorTiles", b.getSpace().floorTiles());
            space.addProperty("lowCeilingTiles", b.getSpace().lowTiles());
            space.addProperty("furnitureTiles", b.getSpace().furnitureTiles());
            space.addProperty("fits", b.getSpace().fits());
            json.add("crowding", space);
        }

        JsonArray functional = new JsonArray();
        for (BlockPos p : b.getFunctionalBlocks()) functional.add(pos(p));
        json.add("functionalPositions", functional);

        // Storage contents are how you tell a villager actually delivered what
        // it gathered, rather than merely having held it at some point.
        if (b instanceof StorageBuilding && level != null) {
            json.add("contents", containerContents(level, b.getFunctionalBlocks(), false));
            // Only what carries a village's mark (the green name): "is the meat the village's own?"
            json.add("villageContents", containerContents(level, b.getFunctionalBlocks(), true));
        }
        if (b instanceof RanchBuilding ranch && level != null) {
            JsonObject ranchJson = new JsonObject();
            ranchJson.addProperty("capacityPerSpecies", ranch.capacityPerSpecies());
            Map<String, Integer> bySpecies = new TreeMap<>();
            for (var animal : ranch.animalsInside(level)) {
                String key = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath()
                        + (animal.isBaby() ? "_baby" : "");
                bySpecies.merge(key, 1, Integer::sum);
            }
            JsonObject animals = new JsonObject();
            bySpecies.forEach(animals::addProperty);
            ranchJson.add("animals", animals);
            json.add("ranch", ranchJson);
        }
        return json;
    }

    /** Aggregated item counts across every container in {@code positions}. */
    private static JsonObject containerContents(ServerLevel level,
                                                Iterable<BlockPos> positions, boolean boundOnly) {
        Map<String, Integer> totals = new TreeMap<>();
        for (var container : com.alex.test.village.building.StorageBuilding.inventories(level, positions)) {
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.peek(slot);
                if (stack.isEmpty()) continue;
                if (boundOnly && !com.alex.test.village.tokens.VillageTokenBinding.isBound(stack)) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                totals.merge(id, stack.getCount(), Integer::sum);
            }
        }
        JsonObject json = new JsonObject();
        totals.forEach(json::addProperty);
        return json;
    }

    private static String typeName(Building b) {
        if (b instanceof TownHallBuilding) return "TownHall";
        if (b instanceof HouseBuilding) return "House";
        if (b instanceof StorageBuilding) return "Storage";
        if (b instanceof RanchBuilding) return "Ranch";
        if (b instanceof KitchenBuilding) return "Kitchen";
        if (b instanceof com.alex.test.village.building.ButcheryBuilding) return "Butchery";
        if (b instanceof ForgeBuilding) return "Forge";
        if (b instanceof MineBuilding) return "Mine";
        if (b instanceof TavernBuilding) return "Tavern";
        if (b instanceof LibraryBuilding) return "Library";
        if (b instanceof com.alex.test.village.building.MageTowerBuilding) return "Mage Tower";
        if (b instanceof SchoolBuilding) return "School";
        if (b instanceof MerchantStallBuilding) return "MerchantStall";
        if (b instanceof BarracksBuilding) return "Barracks";
        if (b instanceof com.alex.test.village.building.ArcheryRangeBuilding) return "ArcheryRange";
        if (b instanceof com.alex.test.village.building.WatchtowerBuilding) return "Watchtower";
        return b.getClass().getSimpleName();
    }

    /** Counts per KnowledgeBase category, plus the concrete tree positions. */
    public static JsonObject knowledge(KnowledgeBase kb) {
        JsonObject json = new JsonObject();
        for (KnowledgeBase.Category category : KnowledgeBase.Category.values()) {
            json.addProperty(category.name().toLowerCase(java.util.Locale.ROOT),
                    kb.count(category));
        }
        JsonArray trees = new JsonArray();
        for (BlockPos p : kb.get(KnowledgeBase.Category.TREE)) trees.add(pos(p));
        json.add("treePositions", trees);
        return json;
    }

    // =========================================================================
    // Villager
    // =========================================================================

    /**
     * One villager's decision-relevant state.
     *
     * <p>{@code activity} is the field most assertions key off — it is the name
     * of whatever the brain currently chose to run, or {@code "none"} when the
     * brain is between activities.
     */
    public static JsonObject villager(VillagerModel model) {
        JsonObject json = new JsonObject();
        json.addProperty("id", shortId(model.npcId));
        json.addProperty("uuid", model.npcId.toString());

        NpcEntity npc = model.puppet();
        json.addProperty("loaded", npc != null && npc.isAlive() && !npc.isRemoved());
        if (npc != null) {
            json.addProperty("profession", npc.getProfession().name());
            json.add("pos", pos(npc.blockPosition()));
            json.addProperty("health", npc.getHealth());
            json.addProperty("sleeping", npc.isSleeping());
            json.addProperty("seated", npc.isChairSeated());
            json.addProperty("animation", npc.getAnimation().name());
            json.add("inventory", inventory(npc.getInventory()));
            SettingsSchemaRegistry.forProfession(npc.getProfession())
                    .ifPresent(schema -> json.add("settings", professionSettings(npc, schema)));
        }

        json.addProperty("moving", model.isMoving());
        // Sub-block position, because a villager oscillating inside one block
        // reads as perfectly stationary at block resolution -- and that is one
        // of the nav failure modes worth catching.
        if (npc != null) {
            JsonObject exact = new JsonObject();
            exact.addProperty("x", round2(npc.getX()));
            exact.addProperty("y", round2(npc.getY()));
            exact.addProperty("z", round2(npc.getZ()));
            json.add("posExact", exact);
            json.addProperty("onGround", npc.onGround());
            json.addProperty("inWater", npc.isInWaterOrBubble());
            json.addProperty("fall", round2(npc.fallDistance));
        }
        if (model.moveGoal() != null) json.add("goal", pos(model.moveGoal()));
        if (model.moveWaypoint() != null) json.add("waypoint", pos(model.moveWaypoint()));
        if (model.isMoving()) {
            json.addProperty("wp", model.moveWaypointIndex());
            json.addProperty("wpCount", model.moveWaypointCount());
            json.addProperty("awaitingPath", model.isAwaitingPath());
        }

        VillagerBrain brain = model.brain();
        if (brain != null) {
            Activity current = brain.currentActivity();
            json.addProperty("activity", current == null ? "none" : current.name());
            json.add("needs", needs(brain.needs()));
            json.add("skills", skills(brain.skills(),
                    npc != null ? npc.getProfession() : Profession.NONE));
            json.add("personality", personality(brain.personality()));
            if (npc != null && npc.level() instanceof ServerLevel sl) {
                ThoughtState thought = ThoughtState.compute(brain, npc, sl);
                json.addProperty("thought", thought.name());
                json.addProperty("thoughtGlyph", thought.glyph());
                json.addProperty("thoughtIcon",
                        thought.icon() == null ? "" : thought.icon().id());
                json.addProperty("memory", brain.memory().describe(sl.getGameTime()));
                json.addProperty("crowdedToday", brain.crowdingTakenToday(sl));
            }
        }
        return json;
    }

    /**
     * Every setting a schema declares, keyed by settingId -- boolean/int for
     * toggles and steppers, a sub-object of entryId -> allowed for tier/allow
     * lists. Generic across every profession with a registered schema; see
     * {@code SettingsSchemaRegistry}.
     */
    public static JsonObject professionSettings(NpcEntity npc, SettingsSchema schema) {
        ProfessionSettings settings = ProfessionSettings.of(npc, schema);
        JsonObject json = new JsonObject();
        for (SettingsSchema.Section section : schema.sections()) {
            for (SettingsSchema.Row row : section.rows()) {
                switch (row.kind()) {
                    case HEADER -> { }
                    case MASTER_TOGGLE, BEHAVIOR_TOGGLE ->
                            json.addProperty(row.settingId(), settings.getBool(row.settingId()));
                    case NUMERIC_STEPPER ->
                            json.addProperty(row.settingId(), settings.getInt(row.settingId()));
                    case CHOICE -> json.addProperty(row.settingId(),
                            row.tierNames().get(settings.getInt(row.settingId())));
                    case TIER_TOGGLES -> {
                        JsonObject entries = new JsonObject();
                        for (String tier : row.tierNames()) {
                            entries.addProperty(tier, settings.isAllowed(row.settingId(), tier));
                        }
                        json.add(row.settingId(), entries);
                    }
                    case ALLOW_LIST -> {
                        JsonObject entries = new JsonObject();
                        for (SettingsSchema.AllowListEntry entry : row.allowListEntries()) {
                            entries.addProperty(entry.id(), settings.isAllowed(row.settingId(), entry.id()));
                        }
                        json.add(row.settingId(), entries);
                    }
                }
            }
        }
        return json;
    }

    /**
     * Competence: the shared intelligence stat plus the skill track for whatever
     * the villager currently does, and every track they have ever earned so a
     * test can prove retraining does not wipe the old one.
     */
    public static JsonObject skills(VillagerSkills skills, Profession current) {
        JsonObject json = new JsonObject();
        json.addProperty("intelligence", skills.intelligence());
        json.addProperty("learningSpeed", skills.learningSpeed(current));
        json.addProperty("level", skills.level(current));
        json.addProperty("experience", skills.experience(current));
        json.addProperty("speedMultiplier", skills.speedMultiplier(current));

        JsonObject byProfession = new JsonObject();
        for (Profession profession : Profession.values()) {
            float xp = skills.experience(profession);
            if (xp > 0f) byProfession.addProperty(profession.name(), xp);
        }
        json.add("byProfession", byProfession);
        return json;
    }

    public static JsonObject needs(VillagerStats stats) {
        JsonObject json = new JsonObject();
        json.addProperty("hunger", stats.hunger);
        json.addProperty("wellbeing", stats.wellbeing);
        // Older external scenarios still read these names; both are views of
        // the same stat now, and can be removed when those scripts migrate.
        json.addProperty("happiness", stats.wellbeing);
        json.addProperty("workMotivation", stats.wellbeing);
        return json;
    }

    /**
     * Personality is derived deterministically from the villager UUID, so
     * including it explains why two villagers in the same state chose
     * differently.
     */
    public static JsonObject personality(Personality p) {
        JsonObject json = new JsonObject();
        json.addProperty("workDrive", p.workDrive);
        json.addProperty("sociability", p.sociability);
        json.addProperty("appetite", p.appetite);
        json.addProperty("workSpeed", p.workSpeed);
        json.addProperty("workFatigue", p.workFatigue);
        json.addProperty("workFraction", p.workFraction);
        json.addProperty("leisureThreshold", p.leisureThreshold());
        return json;
    }

    /** Non-empty slots of the villager's custom inventory. */
    public static JsonArray inventory(InventoryManager inv) {
        JsonArray items = new JsonArray();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack == null || stack.isEmpty()) continue;
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            slot.addProperty("village", com.alex.test.village.tokens.VillageTokenBinding.isBound(stack));
            items.add(slot);
        }
        return items;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static JsonObject pos(BlockPos p) {
        JsonObject json = new JsonObject();
        json.addProperty("x", p.getX());
        json.addProperty("y", p.getY());
        json.addProperty("z", p.getZ());
        return json;
    }

    /** Two decimals: enough to see sub-block drift, short enough for a long timeline. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** Pretty-prints so a failing snapshot is readable straight from disk. */
    public static String toJson(JsonObject obj) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(obj);
    }

    /** Compact form, used for one-line-per-sample timelines. */
    public static String toCompactJson(JsonObject obj) {
        return obj.toString();
    }
}
