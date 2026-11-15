package com.alex.test.village.villagers.builder.project;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.network.BuildOrderPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintException;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import com.alex.test.village.villagers.builder.blueprint.BlueprintWriter;
import com.alex.test.village.villagers.builder.plan.BuilderPlans;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Ordering a build: the player sent a blueprint file ({@link BlueprintUploads}) and the
 * spot. The file is read, turned and written again off the server thread; the spot is
 * checked on it (border, height, buildings, other orders, loaded chunks, at most
 * {@link BuildProjects#MAX_PER_VILLAGE}); then the file is stored and the project added.
 * Nobody builds projects yet: they wait and show as outlines.
 */
public final class BuildOrders {
    private BuildOrders() {}

    /** Players with an order on its way; one at a time. */
    private static final Set<UUID> PENDING = new HashSet<>();

    /** The turned blueprint, its column heights and what it needs. */
    private record Prepared(String name, int sizeX, int sizeY, int sizeZ, int[] low, int[] high, int blocks,
                            Map<Item, Integer> materials, byte[] bytes) {}

    /**
     * May this player order for this Builder: the Builder is loaded and still a Builder, the
     * player may use its settings, and either its desk is open or the player holds a plan for it.
     */
    @Nullable
    public static NpcEntity builderFor(ServerPlayer player, int villagerId) {
        if (!(player.serverLevel().getEntity(villagerId) instanceof NpcEntity npc) || !npc.isAlive()
                || npc.getProfession() != Profession.BUILDER || !npc.canPlayerAccessSettings(player)) {
            return null;
        }
        if (player.containerMenu instanceof BuilderDeskMenu desk && desk.villagerId() == villagerId) return npc;
        BuilderPlans.Plan plan = BuilderPlans.active(player);
        return plan != null && !plan.copy && plan.villagerId == villagerId ? npc : null;
    }

    /** The Builder (null when it is out of sight) and its village, for an order or a storage count. */
    public record Target(@Nullable NpcEntity villager, VillageManager village) {}

    /**
     * Whose village an order goes to. A loaded Builder is checked as in {@link #builderFor}.
     * A Builder out of sight still works for a player holding its plan: the plan keeps the
     * village it was handed out in.
     */
    @Nullable
    public static Target targetFor(ServerPlayer player, int villagerId) {
        BuilderPlans.Plan plan = BuilderPlans.active(player);
        boolean planFor = plan != null && !plan.copy && plan.villagerId == villagerId;
        net.minecraft.world.entity.Entity found = player.serverLevel().getEntity(villagerId);
        boolean loaded = found instanceof NpcEntity && (!planFor || found.getUUID().equals(plan.villagerUuid));
        if (loaded) {
            NpcEntity villager = builderFor(player, villagerId);
            VillageManager village = villager == null ? null : TradesmanSalePolicy.villageOf(villager);
            return village == null ? null : new Target(villager, village);
        }
        if (!planFor || plan.villageId == null) return null;
        VillageManager village = com.alex.test.village.VillageManagerRegistry.get(player.server).get(plan.villageId);
        // Village claims round 7c: as builderFor, only the owner and their Officers order (the plan may predate a rank change).
        if (village == null || !com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                com.alex.test.village.claims.ClaimLock.Act.MANAGE)) return null;
        return new Target(null, village);
    }

    /** How many of each item the village storage would give the Builder (after the player's reserves). */
    public static int[] storageCounts(ServerPlayer player, int villagerId, List<net.minecraft.world.item.Item> items) {
        Target target = targetFor(player, villagerId);
        if (target == null) return new int[0];
        int[] out = new int[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = stored(target.village(), player.serverLevel(), items.get(i));
        return out;
    }

    /**
     * How many of {@code item} a Builder may take from the village's storage: the village's own
     * (a player's blocks in a chest don't count), less what the Stock tab holds back.
     */
    public static int stored(VillageManager village, ServerLevel level, net.minecraft.world.item.Item item) {
        com.alex.test.village.economy.StockReserves reserves = com.alex.test.village.economy.StockReserves.get(level.getServer());
        // free() is "no limit" when nothing is held back, so the real count caps it.
        return Math.max(0, Math.min(reserves.villageTotal(village, level, item),
                reserves.free(village, level, item, Profession.BUILDER)));
    }

    /** {@code give}: the order also takes the blocks it needs from the player's inventory once it is added. */
    public static void order(ServerPlayer player, int villagerId, int uploadId, String name, BlockPos origin,
                             Rotation rotation, Mirror mirror, boolean give) {
        Target target = targetFor(player, villagerId);
        if (target == null) {
            reply(player, false, "Open the Builder's desk, or hold the Builder's plan, to order a build.");
            return;
        }
        byte[] bytes = BlueprintUploads.take(player, uploadId);
        if (bytes == null) {
            reply(player, false, "The blueprint did not arrive. Try again.");
            return;
        }
        VillageManager village = target.village();
        if (village.borderBounds() == null) {
            reply(player, false, "This Builder has no village with a border.");
            return;
        }
        if (BuildProjects.get(player.server).forVillage(village.getVillageId()).size() >= BuildProjects.MAX_PER_VILLAGE) {
            reply(player, false, tooMany());
            return;
        }
        if (!PENDING.add(player.getUUID())) {
            reply(player, false, "Your last order is still being checked.");
            return;
        }
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        CompletableFuture.supplyAsync(() -> prepare(bytes, name, rotation, mirror), Util.backgroundExecutor())
                .whenCompleteAsync((prepared, error) -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                    if (error != null || online == null) {
                        PENDING.remove(playerId);
                        if (online != null) reply(online, false, failure(error));
                        return;
                    }
                    finish(online, villagerId, origin, prepared, give);
                }, server);
    }

    private static Prepared prepare(byte[] bytes, String name, Rotation rotation, Mirror mirror) {
        try {
            Blueprint turned = BlueprintReader.read(bytes, name).transformed(rotation, mirror);
            int sx = turned.sizeX(), sy = turned.sizeY(), sz = turned.sizeZ();
            int[] low = new int[sx * sz], high = new int[sx * sz];
            java.util.Arrays.fill(low, -1);
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        if (turned.paletteIndexAt(x, y, z) == Blueprint.VOID) continue;
                        int column = z * sx + x;
                        if (low[column] < 0) low[column] = y;
                        high[column] = y;
                    }
                }
            }
            BlueprintMaterials.Result materials = BlueprintMaterials.count(turned);
            return new Prepared(shortName(name), sx, sy, sz, low, high, materials.total(), materials.items(),
                    BlueprintWriter.toBytes(turned));
        } catch (BlueprintException e) {
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    /** Server thread: checks the spot again with the turned blueprint, then stores and adds it. */
    private static void finish(ServerPlayer player, int villagerId, BlockPos origin, Prepared prepared, boolean give) {
        Target target = targetFor(player, villagerId);
        VillageManager village = target == null ? null : target.village();
        if (village == null) {
            PENDING.remove(player.getUUID());
            reply(player, false, "The Builder can't take this order now. Open its desk and try again.");
            return;
        }
        ServerLevel level = player.serverLevel();
        BuildProjects projects = BuildProjects.get(player.server);
        String refused = check(level, village, projects, origin, prepared);
        if (refused != null) {
            PENDING.remove(player.getUUID());
            reply(player, false, refused);
            return;
        }
        UUID villageId = village.getVillageId();
        String dimension = level.dimension().location().toString();
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        BlueprintStore.save(server, prepared.bytes()).whenCompleteAsync((hash, error) -> {
            PENDING.remove(playerId);
            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            if (error != null) {
                if (online != null) reply(online, false, "The blueprint could not be saved with the world.");
                return;
            }
            // Checked again: another order may have landed while the file was written.
            BlockPos max = origin.offset(prepared.sizeX() - 1, prepared.sizeY() - 1, prepared.sizeZ() - 1);
            BuildProjects.Project clash = projects.overlapping(dimension, origin, max);
            if (clash != null || projects.forVillage(villageId).size() >= BuildProjects.MAX_PER_VILLAGE) {
                if (online != null) reply(online, false, clash != null ? overlaps(clash) : tooMany());
                return;
            }
            BuildProjects.Project project = new BuildProjects.Project(UUID.randomUUID(), villageId, dimension,
                    prepared.name(), hash, origin, prepared.sizeX(), prepared.sizeY(), prepared.sizeZ(),
                    prepared.blocks(), playerId, level.getGameTime());
            projects.add(project);
            // Read back from the saved file right away (not the copy in hand: the Builder must build what was saved).
            ProjectBlueprints.get(server, hash);
            DynamicVillages.LOGGER.info("[Builder] {} ordered {} at {} ({} blocks)",
                    online != null ? online.getGameProfile().getName() : playerId, prepared.name(), origin.toShortString(),
                    prepared.blocks());
            if (online != null) {
                BuilderPlans.Plan plan = BuilderPlans.active(online);
                if (plan != null && !plan.copy) BuilderPlans.end(online, null);
                String answer = "Ordered: " + prepared.name() + ". It waits for the Builder.";
                // "Give my blocks" was ticked: only now, so a refused order takes nothing.
                if (give) {
                    Gave gave = giveFromInventory(online, village, target.villager(), project, prepared.materials());
                    answer = gave.total() > 0
                            ? String.format(java.util.Locale.ROOT, "Ordered: %s, with %,d of your blocks.",
                                    prepared.name(), gave.total())
                            : "Ordered: " + prepared.name() + (gave.wanted() ? ". You carry none of its blocks."
                                    : ". Blocks given before cover it.");
                }
                reply(online, true, answer);
            }
            changed(server, villageId);
        }, server);
    }

    /** Why the turned blueprint cannot go at {@code origin}, or null when it can. */
    @Nullable
    private static String check(ServerLevel level, VillageManager village, BuildProjects projects, BlockPos origin,
                                Prepared prepared) {
        AABB border = village.borderBounds();
        if (border == null) return "This village has no border yet.";
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int sx = prepared.sizeX(), sy = prepared.sizeY(), sz = prepared.sizeZ();
        if (oy < level.getMinBuildHeight() || oy + sy > level.getMaxBuildHeight()) {
            return "It would stick out of the world there (too high or too low).";
        }
        for (int cx = ox >> 4; cx <= (ox + sx - 1) >> 4; cx++) {
            for (int cz = oz >> 4; cz <= (oz + sz - 1) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return "Part of that spot is not loaded. Go closer and try again.";
            }
        }
        if (projects.forVillage(village.getVillageId()).size() >= BuildProjects.MAX_PER_VILLAGE) return tooMany();
        BuildProjects.Project clash = projects.overlapping(level.dimension().location().toString(), origin,
                origin.offset(sx - 1, sy - 1, sz - 1));
        if (clash != null) return overlaps(clash);
        int minX = (int) Math.floor(border.minX), maxX = (int) Math.floor(border.maxX);
        int minZ = (int) Math.floor(border.minZ), maxZ = (int) Math.floor(border.maxZ);
        List<Building> near = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            BlockPos lo = building.shellMin(), hi = building.shellMax();
            if (hi.getX() >= ox && lo.getX() < ox + sx && hi.getZ() >= oz && lo.getZ() < oz + sz) near.add(building);
        }
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int column = z * sx + x;
                if (prepared.low()[column] < 0) continue;
                int wx = ox + x, wz = oz + z;
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) return "Part of it is outside the village border.";
                int y0 = oy + prepared.low()[column], y1 = oy + prepared.high()[column];
                for (Building building : near) {
                    BlockPos lo = building.shellMin(), hi = building.shellMax();
                    if (wx < lo.getX() || wx > hi.getX() || wz < lo.getZ() || wz > hi.getZ()) continue;
                    if (Math.max(y0, lo.getY()) <= Math.min(y1, hi.getY())) {
                        return "It sits on the " + building.getDisplayName() + ".";
                    }
                }
            }
        }
        return null;
    }

    /** From the desk's Projects tab: pause or resume (toggle), move up or down, or give the player's blocks. */
    public static void act(ServerPlayer player, int villagerId, UUID projectId, int action) {
        NpcEntity builder = builderFor(player, villagerId);
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != villagerId
                || builder == null) {
            return;
        }
        UUID villageId = desk.snapshot().villageId();
        BuildProjects projects = BuildProjects.get(player.server);
        BuildProjects.Project project = projects.byId(projectId);
        if (project == null || villageId == null || !project.villageId.equals(villageId)) return;
        switch (action) {
            case BuildOrderPayloads.PAUSE -> {
                project.paused = !project.paused;
                project.waitUntil = 0;
                project.builderWaits.clear();
                project.status = project.paused ? "Paused" : "Waiting for the Builder";
                projects.touched();
            }
            case BuildOrderPayloads.UP -> projects.move(projectId, -1);
            case BuildOrderPayloads.DOWN -> projects.move(projectId, 1);
            case BuildOrderPayloads.GIVE -> {
                if (!giveForOrder(player, builder, project)) return;
            }
            default -> { return; }
        }
        changed(player.server, villageId);
    }

    /** From the desk's Projects tab: cancel one of this village's orders. */
    public static void cancel(ServerPlayer player, int villagerId, UUID projectId) {
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != villagerId
                || builderFor(player, villagerId) == null) {
            return;
        }
        UUID villageId = desk.snapshot().villageId();
        BuildProjects projects = BuildProjects.get(player.server);
        BuildProjects.Project project = null;
        for (BuildProjects.Project p : projects.forVillage(villageId == null ? new UUID(0, 0) : villageId)) {
            if (p.id.equals(projectId)) project = p;
        }
        if (project == null) return;
        projects.remove(projectId);
        DynamicVillages.LOGGER.info("[Builder] {} cancelled {} at {}", player.getGameProfile().getName(), project.name,
                project.origin.toShortString());
        changed(player.server, villageId);
    }

    // =========================================================================
    // Blocks players give
    // =========================================================================

    /**
     * A player right-clicks the Builder holding blocks an order of its village still needs: the
     * Builder takes what is still missing into the village's {@link GivenBlocks} (they stay the
     * player's). False when it takes nothing; the villager screen opens as usual then.
     */
    public static boolean giveFromHand(NpcEntity builder, ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        VillageManager village = held.isEmpty() ? null : TradesmanSalePolicy.villageOf(builder);
        if (village == null) return false;
        MinecraftServer server = player.server;
        Item item = held.getItem();
        String name = held.getHoverName().getString();
        List<BuildProjects.Project> orders = BuildProjects.get(server).forVillage(village.getVillageId());
        int needed = 0;
        BuildProjects.Project first = null;
        for (BuildProjects.Project project : orders) {
            Blueprint blueprint = ProjectBlueprints.get(server, project.fileHash);
            int still = blueprint == null ? 0 : BuildSite.stillNeeds(blueprint, project, item);
            if (still > 0 && first == null) first = project;
            needed += still;
        }
        if (first == null) {
            // Only a block gets an answer: with a sword or bread in hand the screen just opens.
            if (!orders.isEmpty() && item instanceof BlockItem) say(player, "The Builder doesn't need " + name + " for any order");
            return false;
        }
        if (com.alex.test.village.tokens.VillageTokenBinding.isBound(held)) {
            say(player, "These are the village's " + name + ": put them in storage and the Builder fetches them");
            return false;
        }
        if (!held.getComponentsPatch().isEmpty() || held.isDamageableItem()) {
            say(player, "The Builder takes only plain blocks, nothing named or marked");
            return false;
        }
        GivenBlocks given = GivenBlocks.get(server);
        int missing = needed - given.count(village.getVillageId(), item) - carried(builder, item);
        if (missing <= 0) {
            say(player, "The Builder already has enough " + name + " for its orders");
            return false;
        }
        int amount = Math.min(missing, held.getCount());
        held.shrink(amount);
        given.add(village.getVillageId(), player.getUUID(), item, amount);
        say(player, "The Builder took " + amount + " " + name + " for " + first.name
                + (missing > amount ? " (needs " + (missing - amount) + " more)" : ""));
        player.level().playSound(null, builder.getX(), builder.getY(), builder.getZ(), SoundEvents.ITEM_PICKUP,
                SoundSource.NEUTRAL, 0.4F, 1.2F);
        DynamicVillages.LOGGER.info("[Builder] {} gave {} {} for {}", player.getGameProfile().getName(), amount,
                name, first.name);
        changed(server, village.getVillageId());
        return true;
    }

    /** What a give took, per item, and whether the orders still lacked any of those blocks at all. */
    private record Gave(Map<Item, Integer> items, int total, boolean wanted) {
        /** "64 Stone Bricks, 32 Glass and 2 more", most first. */
        String list() {
            List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(items.entrySet());
            sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                if (i > 0) out.append(i == sorted.size() - 1 ? " and " : ", ");
                out.append(sorted.get(i).getValue()).append(' ')
                        .append(new ItemStack(sorted.get(i).getKey()).getHoverName().getString());
            }
            if (sorted.size() > 3) out.append(" and ").append(sorted.size() - 3).append(" more");
            return out.toString();
        }
    }

    /** The Projects tab's Give: what this order still needs, out of the player's inventory. True when it took any. */
    private static boolean giveForOrder(ServerPlayer player, NpcEntity builder, BuildProjects.Project project) {
        Blueprint blueprint = ProjectBlueprints.get(player.server, project.fileHash);
        if (blueprint == null) {
            notice(player, false, ProjectBlueprints.failed(project.fileHash) ? "This order's blueprint can't be read"
                    : "The blueprint is still loading. Try again in a moment.");
            return false;
        }
        VillageManager village = TradesmanSalePolicy.villageOf(builder);
        if (village == null || !village.getVillageId().equals(project.villageId)) return false;
        Gave gave = giveFromInventory(player, village, builder, project, BuildSite.stillNeeded(blueprint, project));
        if (gave.total() > 0) {
            notice(player, true, String.format(java.util.Locale.ROOT, "Gave the Builder %,d blocks for %s: %s",
                    gave.total(), project.name, gave.list()));
            player.level().playSound(null, builder.getX(), builder.getY(), builder.getZ(), SoundEvents.ITEM_PICKUP,
                    SoundSource.NEUTRAL, 0.4F, 1.2F);
            return true;
        }
        notice(player, !gave.wanted(), gave.wanted() ? "You carry none of the blocks " + project.name + " still needs"
                : "The Builder already has the blocks " + project.name + " still needs");
        return false;
    }

    /**
     * From the Projects tab's give bar: up to {@code amount} of one block for this order. The same
     * caps as Give (never more than the village's orders still lack after what was given and what
     * the Builder carries, plain stacks only); the amount the desk sent is only the most to take.
     */
    public static void giveItem(ServerPlayer player, int villagerId, UUID projectId, Item item, int amount) {
        NpcEntity builder = builderFor(player, villagerId);
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != villagerId
                || builder == null || amount <= 0) {
            return;
        }
        UUID villageId = desk.snapshot().villageId();
        BuildProjects.Project project = BuildProjects.get(player.server).byId(projectId);
        if (project == null || villageId == null || !project.villageId.equals(villageId)) return;
        Blueprint blueprint = ProjectBlueprints.get(player.server, project.fileHash);
        if (blueprint == null) {
            notice(player, false, ProjectBlueprints.failed(project.fileHash) ? "This order's blueprint can't be read"
                    : "The blueprint is still loading. Try again in a moment.");
            return;
        }
        VillageManager village = TradesmanSalePolicy.villageOf(builder);
        if (village == null || !village.getVillageId().equals(project.villageId)) return;
        String name = new ItemStack(item).getHoverName().getString();
        int still = BuildSite.stillNeeds(blueprint, project, item);
        if (still <= 0) {
            notice(player, false, project.name + " needs no more " + name);
            return;
        }
        Gave gave = giveFromInventory(player, village, builder, project, Map.of(item, Math.min(still, amount)));
        if (gave.total() > 0) {
            notice(player, true, String.format(java.util.Locale.ROOT, "Gave the Builder %,d %s for %s",
                    gave.total(), name, project.name));
            player.level().playSound(null, builder.getX(), builder.getY(), builder.getZ(), SoundEvents.ITEM_PICKUP,
                    SoundSource.NEUTRAL, 0.4F, 1.2F);
            changed(player.server, villageId);
            return;
        }
        notice(player, !gave.wanted(), gave.wanted() ? "You carry no plain " + name
                : "The Builder already has all the " + name + " its orders need");
    }

    /**
     * Takes from the player's inventory the blocks of {@code needs} (one order's) that the village's
     * paid orders together still lack after what players gave and what the Builder carries, so
     * never more than the orders can use. Plain stacks only, from the main inventory and the off
     * hand (never armour). They go in the village's {@link GivenBlocks} and stay the player's.
     */
    private static Gave giveFromInventory(ServerPlayer player, VillageManager village, @Nullable NpcEntity builder,
                                          BuildProjects.Project order, Map<Item, Integer> needs) {
        MinecraftServer server = player.server;
        UUID villageId = village.getVillageId();
        Map<Item, Integer> all = ordersStillNeed(server, villageId, order.id);
        needs.forEach((item, n) -> all.merge(item, n, Integer::sum));
        GivenBlocks given = GivenBlocks.get(server);
        List<ItemStack> before = inventoryCopy(player);
        Map<Item, Integer> took = new LinkedHashMap<>();
        int total = 0;
        boolean wanted = false;
        for (Map.Entry<Item, Integer> need : needs.entrySet()) {
            Item item = need.getKey();
            int missing = Math.min(need.getValue(), all.getOrDefault(item, 0) - given.count(villageId, item)
                    - (builder == null ? 0 : carried(builder, item)));
            if (missing <= 0) continue;
            wanted = true;
            int amount = takePlain(player, item, missing);
            if (amount <= 0) continue;
            given.add(villageId, player.getUUID(), item, amount);
            took.put(item, amount);
            total += amount;
        }
        if (total > 0) {
            player.getInventory().setChanged();
            sendChangedSlots(player, before);
            DynamicVillages.LOGGER.info("[Builder] {} gave {} blocks from the inventory for {}",
                    player.getGameProfile().getName(), total, order.name);
        }
        return new Gave(took, total, wanted);
    }

    private static List<ItemStack> inventoryCopy(ServerPlayer player) {
        List<ItemStack> copy = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) copy.add(player.getInventory().getItem(i).copy());
        return copy;
    }

    /**
     * With the desk open (a menu without the player's slots) Minecraft sends inventory changes
     * only once it closes, so the screens would still count the blocks as carried: send the
     * changed slots now.
     */
    private static void sendChangedSlots(ServerPlayer player, List<ItemStack> before) {
        if (player.containerMenu == player.inventoryMenu) return;
        for (int i = 0; i < before.size() && i < player.getInventory().getContainerSize(); i++) {
            ItemStack now = player.getInventory().getItem(i);
            if (ItemStack.matches(before.get(i), now)) continue;
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0, i,
                    now.copy()));
        }
    }

    /**
     * What the village's orders still need, by item, from the blueprints read so far (one
     * not read yet counts nothing and starts reading). {@code skip}: an order left out, or null.
     */
    public static Map<Item, Integer> ordersStillNeed(MinecraftServer server, UUID villageId, @Nullable UUID skip) {
        Map<Item, Integer> all = new HashMap<>();
        for (BuildProjects.Project order : BuildProjects.get(server).forVillage(villageId)) {
            if (order.id.equals(skip)) continue;
            Blueprint blueprint = ProjectBlueprints.get(server, order.fileHash);
            if (blueprint != null) BuildSite.stillNeeded(blueprint, order).forEach((item, n) -> all.merge(item, n, Integer::sum));
        }
        return all;
    }

    /** Takes up to {@code max} plain {@code item} from the player's main inventory, then the off hand. */
    private static int takePlain(ServerPlayer player, Item item, int max) {
        int taken = 0;
        for (List<ItemStack> list : List.of(player.getInventory().items, player.getInventory().offhand)) {
            for (ItemStack stack : list) {
                if (taken >= max) return taken;
                if (!plain(stack, item)) continue;
                int amount = Math.min(max - taken, stack.getCount());
                stack.shrink(amount);
                taken += amount;
            }
        }
        return taken;
    }

    /** A stack the Builder takes from a player: this item, nothing named or marked, not the village's own. */
    public static boolean plain(ItemStack stack, Item item) {
        return stack.is(item) && stack.getComponentsPatch().isEmpty() && !stack.isDamageableItem()
                && !com.alex.test.village.tokens.VillageTokenBinding.isBound(stack);
    }

    /** The village's blocks of {@code item} the Builder carries (they go in before given ones). */
    private static int carried(NpcEntity builder, Item item) {
        int count = 0;
        for (ItemStack stack : builder.getInventory().getSlots()) {
            if (stack.is(item) && builder.getInventory().mayUseAsMaterial(stack)) count += stack.getCount();
        }
        return count;
    }

    /** From the desk's Projects tab: the player's own given {@code item} back, as much as fits. */
    public static void takeBack(ServerPlayer player, int villagerId, Item item) {
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != villagerId) return;
        UUID villageId = desk.snapshot().villageId();
        if (villageId == null) return;
        GivenBlocks given = GivenBlocks.get(player.server);
        int have = 0;
        for (GivenBlocks.Pile pile : given.byGiver(villageId, player.getUUID())) {
            if (pile.item == item) have = pile.count;
        }
        int moved = 0;
        List<ItemStack> before = inventoryCopy(player);
        while (moved < have) {
            ItemStack stack = new ItemStack(item);
            stack.setCount(Math.min(have - moved, stack.getMaxStackSize()));
            int offered = stack.getCount();
            player.getInventory().add(stack);
            moved += offered - stack.getCount();
            if (!stack.isEmpty()) break;
        }
        if (moved <= 0) {
            notice(player, false, have > 0 ? "No room in your inventory" : "Nothing to take back");
            return;
        }
        given.takeBack(villageId, player.getUUID(), item, moved);
        sendChangedSlots(player, before);
        notice(player, true, "Took back " + moved + " " + new ItemStack(item).getHoverName().getString()
                + (moved < have ? " (no room for the rest)" : ""));
        changed(player.server, villageId);
    }

    private static void say(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }

    // =========================================================================
    // Telling clients
    // =========================================================================

    private static void reply(ServerPlayer player, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new BuildOrderPayloads.OrderResult(ok, message));
    }

    /** An answer to a desk button: shown on the open desk (the action bar is hidden behind it). */
    private static void notice(ServerPlayer player, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new BuildOrderPayloads.Notice(ok, message));
    }

    /** After an order or a cancel: outlines for everyone, the list for open desks of this village. */
    public static void changed(MinecraftServer server, UUID villageId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSites(player);
            if (player.containerMenu instanceof BuilderDeskMenu desk && villageId.equals(desk.snapshot().villageId())) {
                sendProjects(player, desk);
            }
        }
    }

    /** The outlines of every order in the player's world. */
    public static void sendSites(ServerPlayer player) {
        String dimension = player.serverLevel().dimension().location().toString();
        List<BuildOrderPayloads.SiteRow> rows = new ArrayList<>();
        for (BuildProjects.Project project : BuildProjects.get(player.server).all()) {
            if (!project.dimension.equals(dimension)) continue;
            rows.add(new BuildOrderPayloads.SiteRow(project.id, project.name, project.origin, project.sizeX,
                    project.sizeY, project.sizeZ));
        }
        PacketDistributor.sendToPlayer(player, new BuildOrderPayloads.Sites(rows));
    }

    /** The desk's Projects tab: this village's orders. */
    public static void sendProjects(ServerPlayer player, BuilderDeskMenu desk) {
        UUID villageId = desk.snapshot().villageId();
        List<BuildOrderPayloads.ProjectRow> rows = new ArrayList<>();
        List<BuildOrderPayloads.GivenRow> given = new ArrayList<>();
        if (villageId != null) {
            for (BuildProjects.Project project : BuildProjects.get(player.server).forVillage(villageId)) {
                // Starts reading the order's file now (once per server run), so its Blocks list is ready when asked.
                ProjectBlueprints.get(player.server, project.fileHash);
                rows.add(new BuildOrderPayloads.ProjectRow(project.id, project.name, project.origin, project.sizeX,
                        project.sizeY, project.sizeZ, project.blocks, project.status,
                        playerName(player.server, project.orderedBy), project.progress, project.paused));
            }
            // Only the viewer's own given blocks: those are the ones they can take back.
            for (GivenBlocks.Pile pile : GivenBlocks.get(player.server).byGiver(villageId, player.getUUID())) {
                given.add(new BuildOrderPayloads.GivenRow(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pile.item), pile.count));
            }
        }
        ServerPlayer freeBy = desk.villager() == null ? null : FreeBuild.owner(desk.villager());
        PacketDistributor.sendToPlayer(player, new BuildOrderPayloads.ProjectsSync(desk.containerId, rows, given,
                freeBy == null ? "" : freeBy.getGameProfile().getName()));
    }

    /**
     * The Projects tab's Blocks list: what one order still places, per item (from the blueprint,
     * from where the Builder is), with what the village has of it and what players gave.
     */
    public static void sendOrderBlocks(ServerPlayer player, int villagerId, UUID projectId) {
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != villagerId
                || !desk.mayAnswerBlocks(player.serverLevel().getGameTime())) {
            return;
        }
        Target target = targetFor(player, villagerId);
        BuildProjects.Project project = BuildProjects.get(player.server).byId(projectId);
        if (target == null || project == null || !project.villageId.equals(target.village().getVillageId())) return;
        Blueprint blueprint = ProjectBlueprints.get(player.server, project.fileHash);
        List<BuildOrderPayloads.BlockNeed> rows = new ArrayList<>();
        if (blueprint != null) {
            GivenBlocks given = GivenBlocks.get(player.server);
            for (Map.Entry<Item, Integer> each : BuildSite.stillNeeded(blueprint, project).entrySet()) {
                Item item = each.getKey();
                // What the Builder already fetched is in its pack, no longer in storage.
                int have = stored(target.village(), player.serverLevel(), item)
                        + (target.villager() == null ? 0 : carried(target.villager(), item));
                rows.add(new BuildOrderPayloads.BlockNeed(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item),
                        each.getValue(), have, given.count(project.villageId, item)));
            }
        }
        PacketDistributor.sendToPlayer(player, new BuildOrderPayloads.OrderBlocksReply(projectId, blueprint != null, rows));
    }

    private static String playerName(MinecraftServer server, @Nullable UUID id) {
        if (id == null) return "";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        var cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(id);
            if (profile.isPresent()) return profile.get().getName();
        }
        return "a player";
    }

    private static String tooMany() {
        return "This village already has " + BuildProjects.MAX_PER_VILLAGE
                + " builds waiting. Cancel one on the desk's Projects tab.";
    }

    private static String overlaps(BuildProjects.Project clash) {
        return "It overlaps the " + clash.name + " already ordered there.";
    }

    private static String failure(@Nullable Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof BlueprintException) return cause.getMessage();
        DynamicVillages.LOGGER.warn("[Builder] Order failed: {}", String.valueOf(cause));
        return "The blueprint could not be read.";
    }

    /** "house_small.litematic" -> "house_small", at most 40 letters. */
    private static String shortName(String name) {
        String out = name;
        int dot = out.lastIndexOf('.');
        if (dot > 0) out = out.substring(0, dot);
        out = out.strip();
        if (out.isEmpty()) out = "Blueprint";
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    // =========================================================================
    // Events
    // =========================================================================

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendSites(player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendSites(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendSites(player);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlueprintUploads.clear(player);
            PENDING.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BlueprintUploads.clearAll();
        PENDING.clear();
    }
}
