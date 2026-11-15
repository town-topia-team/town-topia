package com.alex.test.village.villagers.builder.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import com.alex.test.village.villagers.builder.copy.PlacedLand;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import com.alex.test.village.villagers.builder.project.BuildProjects;
import com.alex.test.village.villagers.builder.project.BuildSite;
import com.alex.test.village.villagers.builder.project.FreeBuild;
import com.alex.test.village.villagers.builder.project.GivenBlocks;
import com.alex.test.village.villagers.builder.project.ProjectBlueprints;
import com.alex.test.village.villagers.builder.settings.BuilderSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Builder building an ordered project ({@link BuildProjects}): walks the cells in
 * {@link BuildSite} order, clears what stands in the way, fetches blocks from storage and
 * places them one at a time. Lives inside {@link BuilderActivity}: when it has nothing to
 * do now (no project, or waiting for blocks) the Builder goes back to its inspections.
 */
final class BuildSiteWork {
    enum Result { WORKING, NOTHING }

    /** How far the Builder reaches from its eyes to a block's middle. */
    private static final double REACH_SQ = 5.0 * 5.0;
    private static final int SCAN_PER_TICK = 512;
    /** How long placing or breaking one block takes (1 s; a setting until 10-25). */
    private static final int BLOCK_TICKS = 20;
    /** Waiting for blocks, a full chest or a room: try again after 30 seconds. */
    private static final int WAIT_TICKS = 600;
    /** A Builder's hold on a project lasts this long without work (an interrupt, the night). */
    private static final int CLAIM_TICKS = 2400;
    private static final int LOOKAHEAD_CELLS = 512, BATCH_ITEMS = 64;
    /** Passes in a row that change nothing before the project ends with blocks skipped. */
    private static final int MAX_IDLE_PASSES = 3;
    /** Waiting for the other Builder's last cell of a layer: this long in place (10 s), then inspections. */
    private static final int STAND_BY_TICKS = 200;

    /** The two ends of an order's layer (10-25): the front works up from the cursor, the back down to meet it. */
    private enum End {
        FRONT, BACK;

        End other() {
            return this == FRONT ? BACK : FRONT;
        }
    }

    private record Hold(UUID project, End end) {}

    /** {@code cell}: the cell it has started on (its end's marker), or -1; the other end never takes it. */
    private record Claim(UUID villager, long until, int cell) {}

    /** Which Builder works which end of which project; in memory only, so a restart frees them all. */
    private static final Map<Hold, Claim> CLAIMS = new HashMap<>();
    /** Orders finished while the other end was held: that Builder takes its leftovers to storage too. */
    private static final Set<UUID> FINISHED_WITH_PARTNER = new HashSet<>();

    @Nullable private UUID projectId;
    /** Its end of that project. */
    private End end = End.FRONT;
    /** The cell it has started on, or -1 (also in its {@link Claim}). */
    private int working = -1;
    /** Ticks it has stood by for the other Builder's last cell of the layer. */
    private int standingBy;
    /** Game time before which it stands still: the other Builder is making what it needs. */
    private long pauseUntil;
    /** What each Builder is making at a station now, by villager (two Builders round 4, 10-25). */
    private static final Map<UUID, Item> MAKING = new HashMap<>();
    /** Looking again after this long (5 s) when the other Builder makes the same block. */
    private static final int PAUSE_TICKS = 100;
    /** The other Builder stands in the cell: wait this long (3 s), once per cell, before putting it off. */
    private static final int PARTNER_IN_CELL_TICKS = 60;
    /** The cell it last waited at for the other Builder to step out of. */
    @Nullable private BlockPos pausedFor;

    /** Where the other Builder on this order stands now, or null when alone or it is out of sight. */
    @Nullable
    private BlockPos partnerPos(Activity.Context ctx, BuildProjects.Project project, long now) {
        Claim other = partner(ctx, project, now);
        net.minecraft.world.entity.Entity body = other == null ? null : ctx.level().getEntity(other.villager());
        return body == null ? null : body.blockPosition();
    }
    @Nullable private Activity sub;
    @Nullable private Item fetching;
    /** What the {@link MakeBuildMaterialsActivity} running makes. */
    @Nullable private Item making;
    /** Items one block of what is being fetched or made takes (2 for a double slab). */
    private int fetchingEach;
    /** The deposit running empties a full pack. */
    private boolean banking;
    /** A full pack was banked so it could make blocks; a second full pack waits instead. */
    private boolean madeRoom;
    private boolean finishing;
    /** The cell being worked and what it needs. */
    @Nullable private BlockPos target;
    @Nullable private BuildSite.Need action;
    /** The last cell walked to; when it is still out of reach after the walk it is put off. */
    @Nullable private BlockPos arrivedFor;
    private int timer;
    private int lastLayer = -1, lastSentProgress = -1;
    private String shownLabel = "";

    // =========================================================================
    // Asked by BuilderActivity
    // =========================================================================

    /** A project in this village the Builder could work on now. */
    static boolean available(Activity.Context ctx) {
        if (!ProfessionSettings.of(ctx.npc(), BuilderSettingsSchema.SCHEMA).getBool(BuilderSettingsSchema.BUILD_ENABLED)) {
            return false;
        }
        long now = ctx.level().getGameTime();
        String dimension = ctx.level().dimension().location().toString();
        for (BuildProjects.Project project : BuildProjects.get(ctx.level().getServer()).forVillage(ctx.mgr().getVillageId())) {
            if (freeEnd(ctx, project, dimension, now) != null) return true;
        }
        return false;
    }

    boolean moving() {
        return sub instanceof MoveActivity;
    }

    Result tick(Activity.Context ctx) {
        if (tripOrderGone(ctx)) {
            // Stopped like an interrupt: what it carries stays in the pack; below it lets go of its own hold only.
            diary(ctx, "BUILDER trip stopped: its order was cancelled or paused");
            sub.stop(ctx);
            sub = null;
            fetching = null;
            making = null;
            MAKING.remove(ctx.npc().getUUID());
        }
        if (sub != null) return tickSub(ctx);
        MinecraftServer server = ctx.level().getServer();
        BuildProjects projects = BuildProjects.get(server);
        long now = ctx.level().getGameTime();
        BuildProjects.Project project = projectId == null ? null : projects.byId(projectId);
        // The other Builder finished it: this one's blocks go to storage too, as after finishing.
        boolean partnerFinished = projectId != null && project == null && FINISHED_WITH_PARTNER.remove(projectId);
        // Back after a long break and another Builder took its end: it looks again (the other end, another order).
        if (project != null && !stillMine(ctx, project, now)) project = null;
        if (project == null || waits(ctx, project, now) || project.paused) {
            // Cancelled, paused or waiting: whatever was half done belongs to it, not to the next one.
            release(ctx);
            timer = 0;
            target = null;
            action = null;
            arrivedFor = null;
            emptyHand(ctx);
            if (partnerFinished && DepositActivity.countDepositable(ctx) > 0) {
                sub = new DepositActivity();
                finishing = true;
                label(ctx, "build_deposit");
                return Result.WORKING;
            }
            project = claim(ctx, projects, now);
            if (project == null) return Result.NOTHING;
        }
        hold(ctx, project.id, now);
        Blueprint blueprint = ProjectBlueprints.get(server, project.fileHash);
        if (blueprint == null) {
            if (ProjectBlueprints.failed(project.fileHash)) {
                project.waitUntil = Long.MAX_VALUE;
                status(ctx, project, "The blueprint file is missing. Cancel this order.");
                release(ctx);
                return Result.NOTHING;
            }
            return Result.WORKING;
        }
        if (pauseUntil > now) return Result.WORKING;
        if (timer > 0) {
            animate(ctx);
            if (--timer == 0) act(ctx, project, blueprint, now);
            return Result.WORKING;
        }
        return next(ctx, project, blueprint, now);
    }

    /**
     * A fetch or making trip whose order was cancelled or paused (bug review round 4): no more village
     * blocks go into it. A walk to a cell or a deposit is short and harmless, so it finishes.
     */
    private boolean tripOrderGone(Activity.Context ctx) {
        if (!WalkFeel.buildErrandStop || projectId == null) return false;
        if (!(sub instanceof FetchFromStorageActivity || sub instanceof MakeBuildMaterialsActivity)) return false;
        BuildProjects.Project project = BuildProjects.get(ctx.level().getServer()).byId(projectId);
        return project == null || project.paused;
    }

    void stop(Activity.Context ctx) {
        if (sub != null) sub.stop(ctx);
        sub = null;
        fetching = null;
        making = null;
        banking = false;
        finishing = false;
        timer = 0;
        target = null;
        action = null;
        arrivedFor = null;
        emptyHand(ctx);
        // The claim stays until it runs out, so this Builder carries on after the interrupt; its cell is free now.
        working(ctx, -1);
        MAKING.remove(ctx.npc().getUUID());
    }

    // =========================================================================
    // Finding the next cell
    // =========================================================================

    private Result next(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, long now) {
        ServerLevel level = ctx.level();
        int cells = BuildSite.cells(blueprint);
        for (int scanned = 0; scanned < SCAN_PER_TICK; scanned++) {
            if (project.cursor >= cells) {
                Result ended = endPhase(ctx, project, blueprint, now);
                if (ended != null) return ended;
                continue;
            }
            if (BuildSite.settleBack(blueprint, project)) {
                // The far-end marker moved to this layer, or the layer was done: look again from the top.
                BuildProjects.get(level.getServer()).touched();
                continue;
            }
            int layer = BuildSite.layer(blueprint, project.cursor);
            if (layer != lastLayer) {
                // A finished layer: the village map and walking routes catch up at once.
                if (lastLayer >= 0 && project.phase != BuildProjects.Project.CLEAR) ctx.mgr().updateMapNow();
                lastLayer = layer;
            }
            int index = mark(project);
            if (index == partnerCell(ctx, project, now)) {
                // The one cell left between the two is the other Builder's: the layer is theirs to finish.
                // It stands by a little (the next layer is usually a moment away), then goes inspecting.
                working(ctx, -1);
                progress(ctx, project, blueprint);
                if (standingBy++ < STAND_BY_TICKS) {
                    emptyHand(ctx);
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    return Result.WORKING;
                }
                standingBy = 0;
                return Result.NOTHING;
            }
            BlockPos local = BuildSite.local(blueprint, project.phase, index);
            BlockPos pos = project.origin.offset(local);
            if (!level.hasChunkAt(pos)) {
                if (WalkFeel.buildUnloaded) {
                    // The far side of a big site can be unloaded while the player is away. Put off, three passes
                    // later the order would end with those blocks missing: this Builder waits and looks again.
                    wait(ctx, project, now, "Part of the site is not loaded. It goes on when you come closer.");
                    return Result.NOTHING;
                }
                defer(ctx, project);
                continue;
            }
            BlockState wanted = BuildSite.wanted(blueprint, local);
            BuildSite.Need need = BuildSite.need(level, pos, wanted, project.phase);
            if (need == BuildSite.Need.NOTHING) {
                advance(project);
                working(ctx, -1);
                continue;
            }
            progress(ctx, project, blueprint);
            working(ctx, index);
            standingBy = 0;
            return begin(ctx, project, blueprint, pos, wanted, need, now);
        }
        progress(ctx, project, blueprint);
        return Result.WORKING;
    }

    /** End of a phase: the next phase, a new pass, a wait, or done. Null means keep scanning. */
    @Nullable
    private Result endPhase(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, long now) {
        BuildProjects projects = BuildProjects.get(ctx.level().getServer());
        if (project.phase < BuildProjects.Project.REST) {
            project.phase++;
            project.cursor = 0;
            project.back = -1;
            lastLayer = -1;
            projects.touched();
            return null;
        }
        if (project.deferred == 0) return finish(ctx, project, 0);
        int skipped = project.deferred;
        boolean idle = project.changedThisPass == 0;
        project.phase = BuildProjects.Project.CLEAR;
        project.cursor = 0;
        project.back = -1;
        project.changedThisPass = 0;
        project.deferred = 0;
        lastLayer = -1;
        if (!idle) {
            project.idlePasses = 0;
            projects.touched();
            return null;
        }
        if (++project.idlePasses >= MAX_IDLE_PASSES) return finish(ctx, project, skipped);
        projects.touched();
        waitAll(ctx, project, now, skipped + (skipped == 1 ? " block" : " blocks") + " can't go in yet. Trying again soon.");
        return Result.NOTHING;
    }

    private Result begin(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, BlockPos pos,
                         BlockState wanted, BuildSite.Need need, long now) {
        ServerLevel level = ctx.level();
        InventoryManager pack = ctx.npc().getInventory();
        // Free Building: nothing dug up is kept and nothing is fetched.
        boolean free = free(ctx);
        if (need == BuildSite.Need.CLEAR) {
            if (ctx.mgr().isProtectedRoomBlock(pos)) {
                wait(ctx, project, now, "A building's room is in the way at " + pos.toShortString());
                return Result.NOTHING;
            }
            if (BuildSite.holdsItems(level, pos)) {
                wait(ctx, project, now, "A chest with items is in the way at " + pos.toShortString());
                return Result.NOTHING;
            }
            if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0) {
                defer(ctx, project);
                return Result.WORKING;
            }
            if (!free && !pack.hasEmptySlot()) return bank(ctx, null);
        } else if (!free) {
            Item item = wanted.getBlock().asItem();
            int each = BlueprintMaterials.itemsPerBlock(wanted);
            if (onHand(ctx, project, item) < each) return fetch(ctx, project, blueprint, item, each, now);
        }
        if (!inReach(ctx, pos)) {
            if (pos.equals(arrivedFor)) {
                defer(ctx, project);
                return Result.WORKING;
            }
            BlockPos avoid = WalkFeel.buildApart ? partnerPos(ctx, project, now) : null;
            BlockPos spot = standSpot(ctx, project, blueprint, pos, avoid);
            // Nowhere else in reach: beside the other Builder is still better than putting the cell off.
            if (spot == null && avoid != null) spot = standSpot(ctx, project, blueprint, pos, null);
            if (spot == null) {
                defer(ctx, project);
                return Result.WORKING;
            }
            target = pos;
            sub = new MoveActivity(spot, VillagerModel.WALK_SPEED, true, true);
            label(ctx, "build_walk");
            return Result.WORKING;
        }
        target = pos;
        action = need;
        timer = BLOCK_TICKS;
        if (need == BuildSite.Need.PLACE) show(ctx, wanted.getBlock().asItem(), timer + 5);
        else emptyHand(ctx);
        doing(ctx, project, "Building");
        label(ctx, "build");
        animate(ctx);
        return Result.WORKING;
    }

    // =========================================================================
    // Clearing and placing
    // =========================================================================

    private void act(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, long now) {
        BlockPos pos = target;
        BuildSite.Need planned = action;
        target = null;
        action = null;
        arrivedFor = null;
        working(ctx, -1);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (pos == null || planned == null) return;
        ServerLevel level = ctx.level();
        BlockPos local = pos.subtract(project.origin);
        BlockState wanted = BuildSite.wanted(blueprint, local);
        // The world may have changed while the Builder worked: look again.
        if (BuildSite.need(level, pos, wanted, project.phase) != planned) return;
        if (planned == BuildSite.Need.CLEAR) {
            clear(ctx, project, pos);
            return;
        }
        place(ctx, project, blueprint, pos, local, wanted);
    }

    private void clear(Activity.Context ctx, BuildProjects.Project project, BlockPos pos) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), ctx.npc(), ItemStack.EMPTY);
        // A door's top or a bed's foot drops nothing: the item comes from the other half, which would
        // break by itself and throw it on the ground. Then that half is the one broken; this one goes with it.
        BlockPos at = pos;
        BlockPos other = WalkFeel.buildPairs && drops.isEmpty() ? BuildSite.otherHalf(level, pos, state) : null;
        if (other != null) {
            BlockState otherState = level.getBlockState(other);
            List<ItemStack> otherDrops = Block.getDrops(otherState, level, other, level.getBlockEntity(other), ctx.npc(),
                    ItemStack.EMPTY);
            if (!otherDrops.isEmpty()) {
                at = other;
                state = otherState;
                drops = otherDrops;
            }
        }
        if (!free(ctx)) {
            // What the Builder digs up is the village's from that moment, like the stone a miner digs.
            for (ItemStack drop : drops) {
                ItemStack owned = drop.isEmpty() ? drop : VillageItemOwnership.produce(drop, ctx.mgr());
                if (!owned.isEmpty() && !ctx.npc().getInventory().addItem(owned)) ItemLossLog.pop(ctx, at, owned, "build_clear");
            }
        }
        level.levelEvent(2001, at, Block.getId(state));
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        PlacedLand.noteRemoved(level, at);
        ctx.mgr().noteBlockChanged(at);
        if (!at.equals(pos) && !level.getBlockState(pos).is(state.getBlock())) {
            // The half this cell asked about went with it.
            PlacedLand.noteRemoved(level, pos);
            ctx.mgr().noteBlockChanged(pos);
        }
        project.changedThisPass++;
        BuildProjects.get(level.getServer()).touched();
    }

    private void place(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, BlockPos pos,
                       BlockPos local, BlockState wanted) {
        ServerLevel level = ctx.level();
        if (!wanted.getCollisionShape(level, pos).isEmpty()) {
            List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, new AABB(pos));
            if (!inside.isEmpty()) {
                // The other Builder stands there: it is usually gone in a moment, so wait 3 s once.
                BlockPos other = WalkFeel.buildApart ? partnerPos(ctx, project, level.getGameTime()) : null;
                if (other != null && !pos.equals(pausedFor)
                        && inside.stream().anyMatch(e -> e.blockPosition().equals(other))) {
                    pausedFor = pos;
                    pauseUntil = level.getGameTime() + PARTNER_IN_CELL_TICKS;
                    return;
                }
                defer(ctx, project);
                return;
            }
        }
        if (!wanted.canSurvive(level, pos)
                || wanted.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
            defer(ctx, project);
            return;
        }
        BlockPos partnerPos = BuildSite.partner(wanted, pos);
        BlockState partnerWanted = partnerPos == null ? null : BuildSite.wanted(blueprint, partnerPos.subtract(project.origin));
        if (partnerWanted != null && !partnerWanted.is(wanted.getBlock())) partnerWanted = null;
        if (partnerWanted != null) {
            BlockState there = level.getBlockState(partnerPos);
            if (!there.isAir() && !there.canBeReplaced() && !there.equals(partnerWanted)) {
                defer(ctx, project);
                return;
            }
        }
        if (!free(ctx)) {
            Item item = wanted.getBlock().asItem();
            int each = BlueprintMaterials.itemsPerBlock(wanted);
            int fromPack = Math.min(carried(ctx, item), each);
            GivenBlocks given = GivenBlocks.get(level.getServer());
            if (fromPack + given.count(project.villageId, item) < each) return; // fetched on the next look
            take(ctx, item, fromPack);
            // The rest from the blocks players gave, the orderer's first.
            if (fromPack < each) given.take(project.villageId, project.orderedBy, item, each - fromPack);
        }
        if (partnerWanted != null) {
            // The other half first, without shape updates, so the pair stands together.
            level.setBlock(partnerPos, partnerWanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        level.setBlock(pos, wanted, Block.UPDATE_ALL);
        // A copy of what this Builder builds keeps its stone walls, even where land was dug out.
        PlacedLand.notePlaced(level, pos, wanted);
        loadBlockEntity(level, blueprint, local, pos);
        if (partnerWanted != null) {
            loadBlockEntity(level, blueprint, partnerPos.subtract(project.origin), partnerPos);
            ctx.mgr().noteBlockChanged(partnerPos);
        }
        SoundType sound = wanted.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
        ctx.mgr().noteBlockChanged(pos);
        advance(project);
        project.changedThisPass++;
        BuildProjects.get(level.getServer()).touched();
    }

    /** Signs, banners and the like get their saved look (contents were stripped when the file was read). */
    private static void loadBlockEntity(ServerLevel level, Blueprint blueprint, BlockPos local, BlockPos pos) {
        CompoundTag saved = blueprint.contains(local.getX(), local.getY(), local.getZ())
                ? blueprint.blockEntityAt(local.getX(), local.getY(), local.getZ()) : null;
        if (saved == null) return;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return;
        // A file may come from anyone: data vanilla lets only operators set is left out, a sign's words aside.
        boolean opOnly = WalkFeel.buildSafeData && entity.onlyOpCanSetNbt();
        if (opOnly && !(entity instanceof SignBlockEntity)) return;
        CompoundTag tag = saved.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        try {
            entity.loadWithComponents(tag, level.registryAccess());
            if (opOnly && entity instanceof SignBlockEntity sign) {
                // A sign's click command runs at operator level for whoever clicks it.
                sign.setText(withoutClicks(sign.getFrontText()), true);
                sign.setText(withoutClicks(sign.getBackText()), false);
            }
            entity.setChanged();
        } catch (RuntimeException e) {
            DynamicVillages.LOGGER.debug("[Builder] Block data at {} not applied: {}", pos.toShortString(), e.toString());
        }
    }

    /** The sign's words and colours, with no click action on any line (the filtered lines too). */
    private static SignText withoutClicks(SignText text) {
        SignText out = text;
        for (int line = 0; line < text.getMessages(false).length; line++) {
            out = out.setMessage(line, withoutClicks(text.getMessage(line, false)),
                    withoutClicks(text.getMessage(line, true)));
        }
        return out;
    }

    private static Component withoutClicks(Component part) {
        MutableComponent out = MutableComponent.create(part.getContents()).setStyle(part.getStyle().withClickEvent(null));
        for (Component sibling : part.getSiblings()) out.append(withoutClicks(sibling));
        return out;
    }

    // =========================================================================
    // Blocks from storage
    // =========================================================================

    private Result fetch(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, Item item, int each,
                         long now) {
        int want = Math.max(1, lookahead(ctx, project, blueprint, item, now));
        int have = onHand(ctx, project, item);
        int amount = Math.min(BATCH_ITEMS, Math.max(1, want - have));
        int stored = BuildOrders.stored(ctx.mgr(), ctx.level(), item);
        if (have + stored < each) {
            // Checked before walking: half a double slab in hand and none in storage waits too.
            return make(ctx, project, item, each, amount, now);
        }
        // Asked with a village-owned sample: the pack has no room at all for an unbound block.
        ItemStack sample = new ItemStack(item);
        VillageTokenBinding.bind(sample, ctx.mgr());
        if (ctx.npc().getInventory().capacityFor(sample) <= 0) return bank(ctx, item);
        fetching = item;
        fetchingEach = each;
        sub = FetchFromStorageActivity.forItem(new ItemStack(item), Math.min(amount, stored), "fetch_build");
        doing(ctx, project, "Fetching " + name(item));
        label(ctx, "fetch_build");
        return Result.WORKING;
    }

    /**
     * None on hand and none in storage: with "Make Missing Blocks" on, makes {@code amount} at a
     * storage room's stonecutter or table, from a player's given blocks first (the orderer's),
     * then from the village's goods. Otherwise waits, saying why it can't (round 5).
     */
    private Result make(Activity.Context ctx, BuildProjects.Project project, Item item, int each, int amount,
                        long now) {
        String waiting = "Waiting for: " + amount + " " + name(item);
        if (!ProfessionSettings.of(ctx.npc(), BuilderSettingsSchema.SCHEMA).getBool(BuilderSettingsSchema.MAKE_MISSING)) {
            wait(ctx, project, now, waiting);
            return Result.NOTHING;
        }
        Claim other = partner(ctx, project, now);
        if (other != null && MAKING.get(other.villager()) == item) {
            // The other Builder is making these right now: look again soon instead of making them twice.
            pauseUntil = now + PAUSE_TICKS;
            emptyHand(ctx);
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            return Result.WORKING;
        }
        for (UUID giver : GivenBlocks.get(ctx.level().getServer()).givers(project.villageId, project.orderedBy)) {
            MaterialPlanner.Plan plan = MakeBuildMaterialsActivity.givenPlan(ctx, giver, item, amount);
            if (plan.any()) return startMaking(ctx, project, plan, giver, item, each);
        }
        MaterialPlanner.Plan plan = MakeBuildMaterialsActivity.villagePlan(ctx, item, amount);
        if (plan.any()) return startMaking(ctx, project, plan, null, item, each);
        if (plan.why() == MaterialPlanner.Why.PACK_FULL && !madeRoom && DepositActivity.countDepositable(ctx) > 0) {
            // Once: a full pack goes to storage, then it plans again.
            madeRoom = true;
            return bank(ctx, null);
        }
        madeRoom = false;
        wait(ctx, project, now, waiting + switch (plan.why()) {
            case NONE, NO_RECIPE -> "";
            case NO_INPUTS -> " (nothing in storage to make it from)";
            case PACK_FULL -> " (no room in the Builder's pack)";
            default -> " (" + plan.why().words + ")";
        });
        return Result.NOTHING;
    }

    private Result startMaking(Activity.Context ctx, BuildProjects.Project project, MaterialPlanner.Plan plan,
                               @Nullable UUID giver, Item item, int each) {
        madeRoom = false;
        making = item;
        MAKING.put(ctx.npc().getUUID(), item);
        fetchingEach = each;
        emptyHand(ctx);
        MakeBuildMaterialsActivity make = new MakeBuildMaterialsActivity(plan, giver);
        sub = make;
        doing(ctx, project, make.line());
        label(ctx, "build_make");
        return Result.WORKING;
    }

    /**
     * How many of this item the next cells of this phase still need, from this Builder's end. With the
     * other end held too, only its own half of the layer, so the two don't fetch for the same cells.
     */
    private int lookahead(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, Item item,
                          long now) {
        boolean pair = partner(ctx, project, now) != null;
        int middle = (project.cursor + project.back) / 2, count = 0;
        if (end == End.FRONT) {
            int last = pair ? middle : Math.min(BuildSite.cells(blueprint), project.cursor + LOOKAHEAD_CELLS) - 1;
            for (int i = project.cursor; i <= last; i++) count += needs(ctx, project, blueprint, item, i);
        } else {
            // The back stays on the cursor's layer (settled before every look), so this never leaves it.
            int last = pair ? middle + 1 : Math.max(project.cursor, project.back - LOOKAHEAD_CELLS + 1);
            for (int i = project.back; i >= last; i--) count += needs(ctx, project, blueprint, item, i);
        }
        return count;
    }

    /** Items of {@code item} cell {@code index} of this phase still needs. */
    private static int needs(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint, Item item,
                             int index) {
        if (BuildSite.doneFromBack(blueprint, project, index)) return 0;
        BlockPos local = BuildSite.local(blueprint, project.phase, index);
        BlockState wanted = BuildSite.wanted(blueprint, local);
        if (wanted == null || wanted.getBlock().asItem() != item) return 0;
        BuildSite.Need need = BuildSite.need(ctx.level(), project.origin.offset(local), wanted, project.phase);
        return need == BuildSite.Need.NOTHING ? 0 : BlueprintMaterials.itemsPerBlock(wanted);
    }

    private Result tickSub(Activity.Context ctx) {
        Activity.Status status = sub.tickOuter(ctx);
        if (status == Activity.Status.RUNNING) {
            // A long trip (making waits up to 2 minutes for a busy table) keeps the hold on the order.
            if (projectId != null) hold(ctx, projectId, ctx.level().getGameTime());
            if (sub instanceof MakeBuildMaterialsActivity make && projectId != null) {
                // "Making 64 Oak Stairs: crafting" on the order while it works.
                BuildProjects.Project project = BuildProjects.get(ctx.level().getServer()).byId(projectId);
                if (project != null) doing(ctx, project, make.line());
            }
            return Result.WORKING;
        }
        sub.stop(ctx);
        Activity done = sub;
        sub = null;
        MAKING.remove(ctx.npc().getUUID());
        if (finishing) {
            finishing = false;
            return Result.NOTHING;
        }
        BuildProjects.Project project = projectId == null ? null : BuildProjects.get(ctx.level().getServer()).byId(projectId);
        if (done instanceof MakeBuildMaterialsActivity make && making != null) {
            Item item = making;
            making = null;
            // A part made is used; with too few for one block it waits, so a failing station is not tried every tick.
            if (project != null && onHand(ctx, project, item) < fetchingEach) {
                String why = make.failed().isEmpty() ? "made too few" : make.failed();
                wait(ctx, project, ctx.level().getGameTime(), "Waiting for: " + name(item) + " (couldn't make it: "
                        + why + ")");
                return Result.NOTHING;
            }
        }
        if (done instanceof MoveActivity) {
            if (status == Activity.Status.FAILED) {
                if (project != null) defer(ctx, project);
            } else {
                arrivedFor = target;
            }
            target = null;
            return Result.WORKING;
        }
        if (done instanceof FetchFromStorageActivity && fetching != null) {
            Item item = fetching;
            fetching = null;
            int have = project == null ? 0 : onHand(ctx, project, item);
            if (have < fetchingEach && project != null) {
                wait(ctx, project, ctx.level().getGameTime(), "Waiting for: " + name(item)
                        + (have == 0 ? " (couldn't take any from storage)" : " (not enough in storage)"));
                return Result.NOTHING;
            }
        }
        if (done instanceof DepositActivity && banking) {
            banking = false;
            // Nothing went in (no storage, or every chest full): trying again at once would loop.
            if (!ctx.npc().getInventory().hasEmptySlot() && project != null) {
                wait(ctx, project, ctx.level().getGameTime(), "The pack is full and storage has no room");
                return Result.NOTHING;
            }
        }
        return Result.WORKING;
    }

    /** A full pack goes to storage with the usual deposit. {@code fetchingFor} stays in the pack. */
    private Result bank(Activity.Context ctx, @Nullable Item fetchingFor) {
        diary(ctx, "BUILDER pack full: taking it to storage");
        sub = fetchingFor == null ? new DepositActivity()
                : DepositActivity.only(stack -> !stack.isEmpty() && !stack.is(fetchingFor)
                        && !VillageItemOwnership.isVillagerFood(stack));
        banking = true;
        label(ctx, "build_deposit");
        return Result.WORKING;
    }

    // =========================================================================
    // Where to stand
    // =========================================================================

    private static boolean inReach(Activity.Context ctx, BlockPos pos) {
        return ctx.npc().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQ;
    }

    /** A walkable spot near the cell, in reach, not where the blueprint puts a solid block. */
    @Nullable
    private static BlockPos standSpot(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint,
                                      BlockPos pos, @Nullable BlockPos avoid) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Vec3 middle = Vec3.atCenterOf(pos);
        NavNode node = graph.nearestRoutable(pos, 4, 4, candidate -> {
            BlockPos feet = candidate.feetPos();
            if (feet.equals(pos) || feet.above().equals(pos) || feet.below().equals(pos)) return false;
            // A block away from the other Builder (round 5), so the two don't stand in each other.
            if (avoid != null && feet.closerThan(avoid, 1.5)) return false;
            if (filled(project, blueprint, feet) || filled(project, blueprint, feet.above())) return false;
            return new Vec3(feet.getX() + 0.5, feet.getY() + 1.6, feet.getZ() + 0.5).distanceToSqr(middle) <= REACH_SQ;
        });
        return node == null ? null : node.feetPos();
    }

    private static boolean filled(BuildProjects.Project project, Blueprint blueprint, BlockPos world) {
        BlockState wanted = BuildSite.wanted(blueprint, world.subtract(project.origin));
        return wanted != null && wanted.blocksMotion();
    }

    // =========================================================================
    // Claims, waiting, status, the end
    // =========================================================================

    /** The end of this order a Builder may take now: the front, else the back (pairs on); null for neither. */
    @Nullable
    private static End freeEnd(Activity.Context ctx, BuildProjects.Project project, String dimension, long now) {
        if (!project.dimension.equals(dimension) || waits(ctx, project, now) || project.paused) return null;
        if (free(ctx, project, End.FRONT, now)) return End.FRONT;
        return WalkFeel.buildPair && free(ctx, project, End.BACK, now) ? End.BACK : null;
    }

    /** Nobody works this order now (it waits), or this Builder waits on its own for it. */
    private static boolean waits(Activity.Context ctx, BuildProjects.Project project, long now) {
        return project.waitUntil > now || project.builderWaits.getOrDefault(ctx.npc().getUUID(), 0L) > now;
    }

    /** Nobody else holds this end: never held, run out, or this Builder's own. */
    private static boolean free(Activity.Context ctx, BuildProjects.Project project, End end, long now) {
        Claim claim = CLAIMS.get(new Hold(project.id, end));
        return claim == null || claim.until() <= now || claim.villager().equals(ctx.npc().getUUID());
    }

    /** Its end is still its own (the back only while pairs are on). */
    private boolean stillMine(Activity.Context ctx, BuildProjects.Project project, long now) {
        return (end == End.FRONT || WalkFeel.buildPair) && free(ctx, project, end, now);
    }

    /** The other Builder on this order, while it still holds the other end; null when alone. */
    @Nullable
    private Claim partner(Activity.Context ctx, BuildProjects.Project project, long now) {
        if (!WalkFeel.buildPair) return null;
        Claim claim = CLAIMS.get(new Hold(project.id, end.other()));
        return claim == null || claim.until() <= now || claim.villager().equals(ctx.npc().getUUID()) ? null : claim;
    }

    /** The cell the other Builder has started on, or -1. */
    private int partnerCell(Activity.Context ctx, BuildProjects.Project project, long now) {
        Claim other = partner(ctx, project, now);
        return other == null ? -1 : other.cell();
    }

    private void hold(Activity.Context ctx, UUID project, long now) {
        CLAIMS.put(new Hold(project, end), new Claim(ctx.npc().getUUID(), now + CLAIM_TICKS, working));
    }

    /** The cell it has started on (-1: none), kept in its hold at once, so the other end leaves it. */
    private void working(Activity.Context ctx, int cell) {
        if (working == cell) return;
        working = cell;
        if (projectId == null) return;
        Hold hold = new Hold(projectId, end);
        Claim claim = CLAIMS.get(hold);
        if (claim != null && claim.villager().equals(ctx.npc().getUUID())) {
            CLAIMS.put(hold, new Claim(claim.villager(), claim.until(), cell));
        }
    }

    /** This Builder's marker: the front works up from the cursor, the back down from {@code back}. */
    private int mark(BuildProjects.Project project) {
        return end == End.FRONT ? project.cursor : project.back;
    }

    private void advance(BuildProjects.Project project) {
        if (end == End.FRONT) project.cursor++;
        else project.back--;
    }

    @Nullable
    private BuildProjects.Project claim(Activity.Context ctx, BuildProjects projects, long now) {
        String dimension = ctx.level().dimension().location().toString();
        for (BuildProjects.Project project : projects.forVillage(ctx.mgr().getVillageId())) {
            End free = freeEnd(ctx, project, dimension, now);
            if (free == null) continue;
            projectId = project.id;
            end = free;
            working = -1;
            lastLayer = -1;
            lastSentProgress = project.progress;
            arrivedFor = null;
            hold(ctx, project.id, now);
            diary(ctx, "BUILDER working on " + project.name + " at " + project.origin.toShortString()
                    + (end == End.BACK ? " (from the far end, with another Builder)" : ""));
            return project;
        }
        return null;
    }

    private void release(Activity.Context ctx) {
        if (projectId != null) {
            Hold hold = new Hold(projectId, end);
            Claim claim = CLAIMS.get(hold);
            // Only its own: after a long break another Builder may hold this end now.
            if (claim != null && claim.villager().equals(ctx.npc().getUUID())) CLAIMS.remove(hold);
        }
        projectId = null;
        end = End.FRONT;
        working = -1;
        pauseUntil = 0;
    }

    /**
     * This Builder waits 30 s for this order (short of blocks, a chest or room in its way); the other
     * Builder, if any, carries on, and the order shows the wait only when nobody else builds it.
     */
    private void wait(Activity.Context ctx, BuildProjects.Project project, long now, String why) {
        project.builderWaits.put(ctx.npc().getUUID(), now + WAIT_TICKS);
        // Asked before release, which forgets its end.
        if (partner(ctx, project, now) == null) status(ctx, project, why);
        else diary(ctx, "BUILDER " + project.name + " (waits alone): " + why);
        emptyHand(ctx);
        target = null;
        action = null;
        release(ctx);
    }

    /** Nobody works this order for 30 s: a pass that changed nothing. */
    private void waitAll(Activity.Context ctx, BuildProjects.Project project, long now, String why) {
        project.waitUntil = now + WAIT_TICKS;
        status(ctx, project, why);
        emptyHand(ctx);
        target = null;
        action = null;
        release(ctx);
    }

    /** What it is doing, on the order's line; with the other end held too, "Building (2 Builders)". */
    private void doing(Activity.Context ctx, BuildProjects.Project project, String text) {
        status(ctx, project, partner(ctx, project, ctx.level().getGameTime()) == null ? text : "Building (2 Builders)");
    }

    /** Puts this Builder's cell off to the next pass and moves its marker on. */
    private void defer(Activity.Context ctx, BuildProjects.Project project) {
        project.deferred++;
        advance(project);
        working(ctx, -1);
        BuildProjects.get(ctx.level().getServer()).touched();
    }

    private void status(Activity.Context ctx, BuildProjects.Project project, String text) {
        if (text.equals(project.status)) return;
        project.status = text;
        BuildProjects.get(ctx.level().getServer()).touched();
        diary(ctx, "BUILDER " + project.name + ": " + text);
        BuildOrders.changed(ctx.level().getServer(), project.villageId);
        lastSentProgress = project.progress;
    }

    private void progress(Activity.Context ctx, BuildProjects.Project project, Blueprint blueprint) {
        int now = BuildSite.progress(blueprint, project.phase,
                project.cursor + BuildSite.cellsDoneFromBack(blueprint, project));
        if (now == project.progress) return;
        project.progress = now;
        BuildProjects.get(ctx.level().getServer()).touched();
        if (Math.abs(now - lastSentProgress) >= 5) {
            lastSentProgress = now;
            BuildOrders.changed(ctx.level().getServer(), project.villageId);
        }
    }

    private Result finish(Activity.Context ctx, BuildProjects.Project project, int skipped) {
        MinecraftServer server = ctx.level().getServer();
        BuildProjects projects = BuildProjects.get(server);
        if (partner(ctx, project, ctx.level().getGameTime()) != null) FINISHED_WITH_PARTNER.add(project.id);
        projects.remove(project.id);
        if (!projects.fileUsed(project.fileHash)) ProjectBlueprints.forget(project.fileHash);
        release(ctx);
        emptyHand(ctx);
        ctx.mgr().updateMapNow();
        String message = "The Builder finished " + project.name
                + (skipped > 0 ? " (" + skipped + (skipped == 1 ? " block" : " blocks") + " could not go in)." : ".");
        diary(ctx, "BUILDER " + message);
        DynamicVillages.LOGGER.info("[Builder] {} at {}", message, project.origin.toShortString());
        ServerPlayer orderer = project.orderedBy == null ? null : server.getPlayerList().getPlayer(project.orderedBy);
        if (orderer != null) orderer.displayClientMessage(Component.literal(message), false);
        BuildOrders.changed(server, project.villageId);
        // Leftovers, dug-up blocks too, go to storage; with nothing to bank, no walk.
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishing = true;
            label(ctx, "build_deposit");
            return Result.WORKING;
        }
        return Result.NOTHING;
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private void animate(Activity.Context ctx) {
        BlockPos pos = target;
        if (pos == null) return;
        VillagerAnimationState pose = action == BuildSite.Need.CLEAR ? VillagerAnimationState.MINE
                : pos.getY() < ctx.npc().getBlockY() ? VillagerAnimationState.PLANT : VillagerAnimationState.FORGE;
        ctx.npc().setAnimation(pose);
        ctx.npc().getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30f, 30f);
    }

    private static void emptyHand(Activity.Context ctx) {
        if (!ctx.npc().getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    /** Free Building is on for this Builder; asked before each block, it may switch itself off. */
    private static boolean free(Activity.Context ctx) {
        return FreeBuild.on(ctx.npc(), ctx.mgr().getVillageId());
    }

    /**
     * The block being placed, in hand for {@code ticks}. A prop, not a real block: a plain
     * block in hand belongs to no village, so the villager threw it on the ground as
     * equipment it may not hold -- one free block for every block placed.
     */
    private static void show(Activity.Context ctx, Item item, int ticks) {
        long until = ctx.level().getGameTime() + ticks;
        ItemStack now = ctx.npc().getMainHandItem();
        if (NpcEntity.isDisplayOnly(now) && now.is(item)) ctx.npc().extendDisplayItem(until);
        else ctx.npc().holdDisplayItem(new ItemStack(item), until);
    }

    /** The village's blocks it carries, the ones it dug up included. */
    private static int carried(Activity.Context ctx, Item item) {
        InventoryManager pack = ctx.npc().getInventory();
        int count = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = pack.getSlot(i);
            if (stack.is(item) && pack.mayUseAsMaterial(stack)) count += stack.getCount();
        }
        return count;
    }

    /** What it can place without a walk: its pack, then the blocks players gave the village. */
    private static int onHand(Activity.Context ctx, BuildProjects.Project project, Item item) {
        return carried(ctx, item) + GivenBlocks.get(ctx.level().getServer()).count(project.villageId, item);
    }

    private static void take(Activity.Context ctx, Item item, int amount) {
        InventoryManager pack = ctx.npc().getInventory();
        int left = amount;
        for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
            ItemStack stack = pack.getSlot(i);
            if (!stack.is(item) || !pack.mayUseAsMaterial(stack)) continue;
            int used = Math.min(left, stack.getCount());
            pack.reduceSlot(i, used);
            left -= used;
        }
    }

    private static String name(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    private void label(Activity.Context ctx, String text) {
        if (text.equals(shownLabel)) return;
        shownLabel = text;
        ctx.npc().setActivityLabel(text);
    }

    private static void diary(Activity.Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
