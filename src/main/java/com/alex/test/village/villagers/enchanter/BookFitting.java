package com.alex.test.village.villagers.enchanter;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.brain.LeisureActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.brain.WanderActivity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.blacksmith.SmithStrikes;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Having an enchanted book put on a tool. The worker takes the book from
 * storage, walks over to the blacksmith or the enchanter, whichever has the
 * shorter line (see {@link #findHelper}), and asks:
 * <ol>
 *   <li>a short talk: the worker says hello, holds up the book, then the
 *       tool; the smith thinks of the anvil and nods;</li>
 *   <li>the smith takes the tool to its anvil (the enchanter to its table)
 *       and works the book in for a few seconds, the worker waiting beside it;</li>
 *   <li>the smith hands the tool back, the worker holds it up pleased and
 *       says thanks.</li>
 * </ol>
 *
 * <p>Nothing changes hands for real until the book goes in: the book and the
 * tool stay in the worker's pack the whole time and both sides only hold up
 * display copies, so a scene broken off halfway (a villager called away,
 * unloaded or hurt) loses nothing; the worker just keeps the book, uses it
 * on its next visit or banks it.
 *
 * <p>The two sides run in their own activities and meet in a {@link Job}:
 * the worker's {@link Visit}, a work session of its own that replaces the
 * worker's normal one while a useful book waits in storage, and the smith's
 * {@link Serve}, which its work activity runs between two of its own steps
 * as soon as a customer stands beside it. A smith resting in the square is
 * called to work for it by the brain's urgent-work check.
 */
public final class BookFitting {

    private static final int LINE_TICKS = 40;
    /**
     * Time at the anvil or table working the book in: at the anvil one whole loop of
     * smith_enchant_press or _tap (100 ticks) after its 8-tick blend in.
     */
    private static final int WORK_TICKS = 108;
    /** Close enough to talk; ConverseActivity's own pairing range is 2.5. */
    private static final double TALK_RANGE_SQ = 3.0 * 3.0;
    /** Longest a worker waits for a busy smith before giving up. */
    private static final int MAX_WAIT_TICKS = 20 * 60;
    /** A side not seen for this long has gone; the other drops the scene. */
    private static final int STALE_TICKS = 60;
    /** A failed or turned-away trip tries again after a random one to four minutes, so workers turned away together do not all come back together. */
    private static final int RETRY_MIN_TICKS = 20 * 60;
    private static final int RETRY_MAX_TICKS = 20 * 60 * 4;
    private static final int RETRY_AFTER_DONE = 20 * 5;
    /** How long a "should I go?" answer is reused: it reads every chest. */
    private static final int CHECK_TTL = 200;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    private static final String TAG_RETRY_AT = "BookFitRetryAt";

    public enum Phase { ASKING, TALKING, WORKING, HANDING_BACK, DONE, FAILED }

    /** One fitting in progress, shared by the worker's and the helper's activities. */
    static final class Job {
        final UUID worker;
        final UUID helper;
        final ItemStack book;
        final EnchantNeeds.WorkTool tool;
        final Building building;
        final BlockPos station;
        final boolean anvil;
        Phase phase = Phase.ASKING;
        long workerSeen;
        long helperSeen;
        /** What the worker held before it held things up, put back afterwards. */
        ItemStack workerHand = ItemStack.EMPTY;

        Job(UUID worker, UUID helper, ItemStack book, EnchantNeeds.WorkTool tool,
            Building building, BlockPos station, boolean anvil) {
            this.worker = worker;
            this.helper = helper;
            this.book = book;
            this.tool = tool;
            this.building = building;
            this.station = station;
            this.anvil = anvil;
        }

        boolean over() { return phase == Phase.DONE || phase == Phase.FAILED; }
    }

    private static final Map<UUID, Job> BY_HELPER = new HashMap<>();

    /** Helpers whose anvil or table could not be reached, and until when workers leave them be. */
    private static final Map<UUID, Long> STATION_UNREACHABLE = new HashMap<>();
    private static final int UNREACHABLE_TICKS = 20 * 60 * 5;

    private record Checked(long time, boolean result) {}
    private static final Map<UUID, Checked> CHECKS = new HashMap<>();

    /**
     * Spots booked with each helper: worker id to the last tick its trip was
     * seen. A worker books before it sets off, so a smith never has more than
     * {@link #MAX_BOOKINGS} customers coming (one served, one waiting) and the
     * rest keep working instead of queueing at the anvil.
     */
    private static final Map<UUID, Map<UUID, Long>> BOOKINGS = new HashMap<>();
    private static final int MAX_BOOKINGS = 2;
    /** A booking whose trip has not ticked for this long is freed (worker unloaded or gone). */
    private static final int BOOKING_STALE_TICKS = 200;
    /** Longest a whole trip may take before the worker gives up and frees its spot. */
    private static final int MAX_TRIP_TICKS = 20 * 60 * 5;
    /**
     * Below this a book is a small gain (a second level of something the tool
     * already has, or a light one like armour Unbreaking): such a trip only
     * goes when a line is empty, so bigger upgrades are served first.
     * New Unbreaking on an axe scores 36, a raised one 18.
     */
    private static final int SMALL_GAIN = 30;

    /** Storage books set aside for a worker on its way to fetch them, so no one else chases the same book. */
    private record Reserved(ItemStack book, long since) {}
    private static final Map<UUID, Reserved> RESERVED_BOOKS = new HashMap<>();

    private BookFitting() {}

    // =========================================================================
    // Bookings and reserved books
    // =========================================================================

    /** Live bookings with {@code helper} other than {@code worker}'s own. */
    private static int bookedByOthers(UUID helper, UUID worker, long now) {
        Map<UUID, Long> booked = BOOKINGS.get(helper);
        if (booked == null) return 0;
        booked.entrySet().removeIf(e -> e.getValue() > now
                || VillagerPause.stale(e.getKey(), e.getValue(), BOOKING_STALE_TICKS, now));
        int others = 0;
        for (UUID id : booked.keySet()) if (!id.equals(worker)) others++;
        return others;
    }

    /** Takes a spot with {@code helper}; false when others already hold {@code spots} or more. */
    private static boolean book(UUID helper, UUID worker, long now, int spots) {
        if (bookedByOthers(helper, worker, now) >= spots) return false;
        BOOKINGS.computeIfAbsent(helper, k -> new HashMap<>()).put(worker, now);
        return true;
    }

    /**
     * Keeps a spot already held alive. One that lapsed is taken again only
     * while a spot is free, so a worker held up elsewhere cannot make a third
     * in line; the small-gain rule only applies when first booking.
     */
    private static boolean keepSpot(UUID helper, UUID worker, long now) {
        Map<UUID, Long> booked = BOOKINGS.get(helper);
        Long seen = booked == null ? null : booked.get(worker);
        if (seen != null && seen <= now && !VillagerPause.stale(worker, seen, BOOKING_STALE_TICKS, now)) {
            booked.put(worker, now);
            return true;
        }
        return book(helper, worker, now, MAX_BOOKINGS);
    }

    private static void unbook(UUID helper, UUID worker) {
        Map<UUID, Long> booked = BOOKINGS.get(helper);
        if (booked == null) return;
        booked.remove(worker);
        if (booked.isEmpty()) BOOKINGS.remove(helper);
    }

    /** Books other workers are on their way to fetch from storage. */
    private static List<ItemStack> reservedByOthers(UUID worker, long now) {
        RESERVED_BOOKS.values().removeIf(r -> now - r.since() > MAX_TRIP_TICKS || r.since() > now);
        List<ItemStack> books = new ArrayList<>();
        for (Map.Entry<UUID, Reserved> entry : RESERVED_BOOKS.entrySet()) {
            if (!entry.getKey().equals(worker)) books.add(entry.getValue().book());
        }
        return books;
    }

    /** Whether {@code book} is one of the set-aside copies; each copy covers one book in storage. */
    private static boolean takeReserved(List<ItemStack> reserved, ItemStack book) {
        for (int i = 0; i < reserved.size(); i++) {
            if (ItemStack.isSameItemSameComponents(reserved.get(i), book)) {
                reserved.remove(i);
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Who can help, and with which book
    // =========================================================================

    /** A villager who can put a book on: a smith at its anvil, or an enchanter at its table. */
    record Helper(VillagerModel model, NpcEntity npc, Building building, BlockPos station, boolean anvil) {}

    /**
     * The smith or enchanter with the shortest line, the nearest of those when
     * lines tie. Both count alike, so the enchanter takes customers too instead
     * of the whole village queueing at the forge. Null when none has a spot.
     *
     * @param spots how many of a helper's spots this worker may count on:
     *              {@link #MAX_BOOKINGS} normally, 1 for a small gain (only an empty line)
     */
    @Nullable
    static Helper findHelper(Activity.Context ctx, int spots) {
        long now = ctx.level().getGameTime();
        Helper best = null;
        int bestLine = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            Profession profession = npc.getProfession();
            if (profession != Profession.BLACKSMITH && profession != Profession.ENCHANTER) continue;
            if (model.npcId.equals(ctx.model().npcId)) continue;
            Long blockedUntil = STATION_UNREACHABLE.get(model.npcId);
            if (blockedUntil != null && blockedUntil > now) continue;
            int line = bookedByOthers(model.npcId, ctx.model().npcId, now);
            if (line >= spots) continue;
            Activity.Context helperCtx = new Activity.Context(model.brain(), model, ctx.mgr(), ctx.level(), npc);
            if (!VillagerRegistry.shouldWorkNow(profession, helperCtx)) continue;
            if (!available(model.brain().currentActivity(), profession)) continue;
            Helper helper = profession == Profession.BLACKSMITH
                    ? atForge(ctx, model, npc) : atLibrary(ctx, model, npc);
            if (helper == null) continue;
            double dist = npc.distanceToSqr(ctx.npc());
            if (line < bestLine || (line == bestLine && dist < bestDist)) {
                bestLine = line;
                bestDist = dist;
                best = helper;
            }
        }
        return best;
    }

    /** Spots a trip for this book may count on: a small gain only goes when a line is empty. */
    private static int spotsFor(Pick pick) {
        return pick.score() < SMALL_GAIN ? 1 : MAX_BOOKINGS;
    }

    /** Busy with its own work, or idling: either can stop for a customer. Eating, sleeping or out for the evening cannot. */
    private static boolean available(@Nullable Activity current, Profession profession) {
        if (current == null || current instanceof LeisureActivity || current instanceof WanderActivity) return true;
        return profession == Profession.BLACKSMITH
                ? current instanceof BlacksmithActivity : current instanceof EnchanterActivity;
    }

    @Nullable
    private static Helper atForge(Activity.Context ctx, VillagerModel model, NpcEntity npc) {
        if (!ProfessionSettings.of(npc, BlacksmithSettingsSchema.SCHEMA).getBool(BlacksmithSettingsSchema.WORK_ENABLED))
            return null;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof ForgeBuilding forge && forge.isValid() && forge.getAnvilPos() != null) {
                return new Helper(model, npc, forge, forge.getAnvilPos(), true);
            }
        }
        return null;
    }

    @Nullable
    private static Helper atLibrary(Activity.Context ctx, VillagerModel model, NpcEntity npc) {
        if (!ProfessionSettings.of(npc, EnchanterSettingsSchema.SCHEMA).getBool(EnchanterSettingsSchema.WORK_ENABLED))
            return null;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof LibraryBuilding library && library.isValid()
                    && library.getEnchantingTablePos() != null) {
                return new Helper(model, npc, library, library.getEnchantingTablePos(), false);
            }
        }
        return null;
    }

    /** A book that would raise one of the worker's tools, the tool, whether it is already in the pack, and how much it helps. */
    record Pick(EnchantNeeds.WorkTool tool, ItemStack book, boolean carried, int score) {}

    /**
     * The most useful book for this worker's tools: one it carries first (left
     * from a visit that was broken off), else one in storage. Only books for
     * what the job wants count: a Sharpness book does fit an axe, but a
     * lumberjack has no use for it.
     */
    @Nullable
    static Pick pickBook(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        List<EnchantNeeds.WorkTool> tools = new ArrayList<>();
        for (EnchantNeeds.WorkTool tool : EnchantNeeds.workTools(npc, ctx)) {
            if (EnchantNeeds.worthABook(tool.stack(npc))) tools.add(tool);
        }
        if (tools.isEmpty()) return null;

        Pick best = null;
        int bestScore = 0;
        InventoryManager inv = npc.getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack book = inv.getSlot(i);
            if (!book.is(Items.ENCHANTED_BOOK) || !inv.mayUse(book) || BookOrders.isOrdered(book)) continue;
            for (EnchantNeeds.WorkTool tool : tools) {
                int score = score(tool, tool.stack(npc), book);
                if (score > 0 && score + 1000 > bestScore) {
                    bestScore = score + 1000;
                    best = new Pick(tool, book.copy(), true, score);
                }
            }
        }
        // A book needs a slot of its own: with a full pack the trip could only fail.
        if (best != null || !inv.hasEmptySlot()) return best;
        List<ItemStack> reserved = reservedByOthers(ctx.model().npcId, ctx.level().getGameTime());
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(ctx.level())) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack book = chest.peek(i);
                    if (!book.is(Items.ENCHANTED_BOOK) || !storage.mayUse(ctx.level(), book)
                            || BookOrders.isOrdered(book)) continue;
                    // Another worker is already on its way for this one.
                    if (takeReserved(reserved, book)) continue;
                    for (EnchantNeeds.WorkTool tool : tools) {
                        int score = score(tool, tool.stack(npc), book);
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Pick(tool, book.copyWithCount(1), false, score);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static int score(EnchantNeeds.WorkTool tool, ItemStack stack, ItemStack book) {
        return EnchantNeeds.score(tool, stack, book);
    }

    /**
     * Whether this worker's next work session should be a trip to have a book
     * put on. Cheap enough for brain scoring: the answer is reused for
     * {@link #CHECK_TTL} ticks, and a failed trip waits one to four minutes.
     */
    public static boolean shouldVisit(Activity.Context ctx, Profession profession) {
        if (!EnchantNeeds.usesBooks(profession)) return false;
        long now = ctx.level().getGameTime();
        if (now < ctx.npc().getBrainData().getLong(TAG_RETRY_AT)) return false;
        if (profession.isDefender() && GuardAlertSystem.hasEmergencyForGuard(ctx)) return false;
        Checked checked = CHECKS.get(ctx.model().npcId);
        if (checked != null && now >= checked.time() && now - checked.time() < CHECK_TTL) return checked.result();
        Pick pick = pickBook(ctx);
        boolean result = pick != null && findHelper(ctx, spotsFor(pick)) != null;
        CHECKS.put(ctx.model().npcId, new Checked(now, result));
        return result;
    }

    private static int retryLater(Activity.Context ctx) {
        return RETRY_MIN_TICKS + ctx.npc().getRandom().nextInt(RETRY_MAX_TICKS - RETRY_MIN_TICKS + 1);
    }

    private static void retryAfter(Activity.Context ctx, int ticks) {
        ctx.npc().getBrainData().putLong(TAG_RETRY_AT, ctx.level().getGameTime() + ticks);
        CHECKS.remove(ctx.model().npcId);
    }

    // =========================================================================
    // The helper's side
    // =========================================================================

    /** Whether a worker stands beside this smith or enchanter waiting to be served. */
    public static boolean hasCustomer(Activity.Context ctx) {
        Job job = BY_HELPER.get(ctx.model().npcId);
        if (job == null) return false;
        long now = ctx.level().getGameTime();
        if (job.over() || VillagerPause.stale(job.worker, job.workerSeen, STALE_TICKS, now)) {
            BY_HELPER.remove(ctx.model().npcId);
            return false;
        }
        return job.phase == Phase.ASKING;
    }

    /** The scene for the helper's work activity to run as a step of its own; null when nobody is waiting. */
    @Nullable
    public static Activity serve(Activity.Context ctx) {
        Job job = BY_HELPER.get(ctx.model().npcId);
        return job != null && job.phase == Phase.ASKING ? new Serve(job) : null;
    }

    /** The smith's (or enchanter's) half: talk, work the book in, hand the tool back. */
    static final class Serve extends Activity {

        private final Job job;
        @Nullable private ConverseActivity talk;
        @Nullable private MoveActivity move;
        private int workTimer;
        private boolean atStation;
        /** The smith's clip at the anvil (pressed on or tapped in), picked per fitting. */
        private VillagerAnimationState anvilClip = VillagerAnimationState.SMITH_ENCHANT;
        private int waitTicks;

        Serve(Job job) {
            this.job = job;
        }

        @Override
        public String name() { return "fit_book"; }

        @Override
        protected Status tick(Context ctx) {
            long now = ctx.level().getGameTime();
            job.helperSeen = now;
            NpcEntity worker = workerEntity(ctx, job);
            if (worker == null || job.phase == Phase.FAILED
                    || VillagerPause.stale(job.worker, job.workerSeen, STALE_TICKS, now)) {
                job.phase = Phase.FAILED;
                return Status.FAILED;
            }
            switch (job.phase) {
                case ASKING -> {
                    talk = ConverseActivity.business(worker, askScript(job),
                            (lineCtx, index) -> askLine(lineCtx, worker, index));
                    job.phase = Phase.TALKING;
                    diary(ctx, "FIT talking with " + EnchantNeeds.nameOf(worker) + " about "
                            + EnchantBooks.describe(job.book) + " for its " + job.tool.label());
                    return Status.RUNNING;
                }
                case TALKING -> {
                    if (talk != null && talk.tickOuter(ctx) == Status.RUNNING) return Status.RUNNING;
                    if (talk != null) talk.stop(ctx);
                    talk = null;
                    job.phase = Phase.WORKING;
                    holdTool(ctx, worker, ctx.npc(), toolNow(worker), WORK_TICKS + 400);
                    worker.clearDisplayItem();
                    return Status.RUNNING;
                }
                case WORKING -> {
                    return tickWork(ctx, worker);
                }
                case HANDING_BACK -> {
                    if (talk == null) {
                        if (ctx.npc().distanceToSqr(worker) > TALK_RANGE_SQ) {
                            // The worker is still on its way over from where it waited.
                            if (++waitTicks > 200) return finish(ctx, worker, true);
                            look(ctx.npc(), worker);
                            return Status.RUNNING;
                        }
                        talk = ConverseActivity.business(worker, thanksScript(),
                                (lineCtx, index) -> thanksLine(lineCtx, worker, index));
                        return Status.RUNNING;
                    }
                    if (talk.tickOuter(ctx) == Status.RUNNING) return Status.RUNNING;
                    talk.stop(ctx);
                    talk = null;
                    return finish(ctx, worker, true);
                }
                default -> {
                    return job.phase == Phase.DONE ? Status.DONE : Status.FAILED;
                }
            }
        }

        private Status tickWork(Context ctx, NpcEntity worker) {
            if (!atStation) {
                if (move == null) {
                    BlockPos stand = job.building.getWalkTargetBeside(
                            job.station, ctx.mgr().getGraph(), ctx.npc().blockPosition());
                    if (stand == null) return unreachable(ctx, worker);
                    // On the tile itself: "near" (2 blocks, or a tile off) was measured through walls, and the
                    // smith fitted books outside the forge (10-25).
                    if (NavGraph.feetCell(ctx.npc()).equals(stand)) {
                        atStation = true;
                        startWork(ctx);
                        return Status.RUNNING;
                    }
                    move = new MoveActivity(stand, WALK_SPEED, true);
                }
                Status status = move.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
                move.stop(ctx);
                move = null;
                if (status == Status.FAILED) {
                    diary(ctx, "FIT could not reach the " + (job.anvil ? "anvil" : "table") + " at "
                            + job.station.toShortString());
                    return unreachable(ctx, worker);
                }
                atStation = true;
                startWork(ctx);
                return Status.RUNNING;
            }
            ctx.model().stopMoving();
            look(ctx.npc(), job.station);
            workTimer--;
            if (job.anvil) SmithStrikes.tick(ctx.level(), job.station, anvilClip, WORK_TICKS - workTimer - 1);
            if (workTimer % 30 == 0) {
                if (!job.anvil) ctx.level().playSound(null, job.station, SoundEvents.BOOK_PAGE_TURN,
                        SoundSource.BLOCKS, 0.6f, 1.1f);
                ctx.level().sendParticles(ParticleTypes.ENCHANT, job.station.getX() + 0.5,
                        job.station.getY() + 1.2, job.station.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.4);
            }
            if (workTimer > 0) return Status.RUNNING;

            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            if (!apply(ctx, worker)) return finish(ctx, worker, false);
            ctx.level().playSound(null, job.station, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.8f, 1.0f);
            ctx.level().playSound(null, job.station, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7f, 1.2f);
            ctx.level().sendParticles(ParticleTypes.ENCHANT, job.station.getX() + 0.5,
                    job.station.getY() + 1.2, job.station.getZ() + 0.5, 30, 0.5, 0.4, 0.5, 0.6);
            holdTool(ctx, worker, ctx.npc(), toolNow(worker), 400);
            job.phase = Phase.HANDING_BACK;
            return Status.RUNNING;
        }

        private void startWork(Context ctx) {
            workTimer = WORK_TICKS;
            ctx.model().stopMoving();
            if (job.anvil) anvilClip = SmithStrikes.enchantClip(ctx.level().random);
            ctx.npc().setAnimation(job.anvil ? anvilClip : VillagerAnimationState.CRAFTING);
            ctx.npc().setActivityLabel("fit book");
        }

        /**
         * The book goes in: applied to the tool in the worker's pack (or worn
         * armour) with anvil rules, and the book taken out of the pack.
         */
        private boolean apply(Context ctx, NpcEntity worker) {
            ItemStack tool = job.tool.stack(worker);
            if (tool.isEmpty()) return false;
            InventoryManager inv = worker.getInventory();
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack book = inv.getSlot(i);
                if (!ItemStack.isSameItemSameComponents(book, job.book)) continue;
                ItemStack handBefore = worker.getMainHandItem();
                boolean handWasTool = job.tool.equipment() == null && !handBefore.isEmpty()
                        && !NpcEntity.isDisplayOnly(handBefore) && handBefore.is(tool.getItem());
                if (!EnchantBooks.applyBook(tool, book)) return false;
                inv.reduceSlot(i, 1);
                // Worn armour changed in place: set it again so players see the new shine.
                if (job.tool.equipment() != null) worker.setItemSlot(job.tool.equipment(), tool.copy());
                // The hand shows a copy of the pack's tool: show the new one.
                if (handWasTool) worker.setItemSlot(EquipmentSlot.MAINHAND, tool.copy());
                if (job.workerHand.is(tool.getItem()) && job.tool.equipment() == null) {
                    job.workerHand = tool.copy();
                }
                diary(ctx, "FIT put " + EnchantBooks.describe(job.book) + " on " + EnchantNeeds.nameOf(worker)
                        + "'s " + job.tool.label());
                if (job.anvil) {
                    ctx.brain().onBlacksmithExperience(ctx, BlacksmithBalance.RECYCLE_POINTS);
                } else {
                    ctx.brain().onEnchanterExperience(ctx, EnchanterBalance.EXPERIENCE_PER_BINDING);
                }
                return true;
            }
            return false;
        }

        /** The station cannot be reached: no more customers sent here for a while. */
        private Status unreachable(Context ctx, NpcEntity worker) {
            STATION_UNREACHABLE.put(ctx.model().npcId, ctx.level().getGameTime() + UNREACHABLE_TICKS);
            return finish(ctx, worker, false);
        }

        private Status finish(Context ctx, NpcEntity worker, boolean done) {
            job.phase = done ? Phase.DONE : Phase.FAILED;
            ctx.npc().clearDisplayItem();
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            BY_HELPER.remove(ctx.model().npcId, job);
            return done ? Status.DONE : Status.FAILED;
        }

        @Override
        public void stop(Context ctx) {
            if (talk != null) { talk.stop(ctx); talk = null; }
            if (move != null) { move.stop(ctx); move = null; }
            if (!job.over()) job.phase = Phase.FAILED;
            BY_HELPER.remove(ctx.model().npcId, job);
            ctx.npc().clearDisplayItem();
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }

        // ---- the two talks ---------------------------------------------------

        /** Worker: hello, the book, the tool. Helper: hello, the anvil (or table) with a nod. */
        private ConversationScript askScript(Job job) {
            ThoughtIcon station = job.anvil ? ThoughtIcon.TOPIC_SMITHING : ThoughtIcon.TOPIC_ENCHANTING;
            return new ConversationScript(List.of(
                    new ConversationScript.Line(false, ThoughtIcon.EMOTE_GREETING),
                    new ConversationScript.Line(true, ThoughtIcon.EMOTE_GREETING),
                    new ConversationScript.Line(false, ThoughtIcon.TOPIC_ENCHANTING),
                    new ConversationScript.Line(false, ThoughtIcon.TOPIC_TOOLS),
                    new ConversationScript.Line(true, station),
                    new ConversationScript.Line(true, ThoughtIcon.EMOTE_AGREE)),
                    LINE_TICKS, List.of(0, 6, 8, 10, 14, 6), List.of("enchanting"));
        }

        private ThoughtIcon askLine(Context ctx, NpcEntity worker, int index) {
            long until = ctx.level().getGameTime() + LINE_TICKS + 30;
            switch (index) {
                case 2 -> worker.holdDisplayItem(job.book, until);
                case 3 -> worker.holdDisplayItem(toolNow(worker), until);
                case 4 -> {
                    worker.clearDisplayItem();
                    ctx.npc().nod();
                }
                case 5 -> ctx.npc().nod();
                default -> { }
            }
            return askScript(job).line(index).icon();
        }

        /** Helper: holds the tool out. Worker: takes it, holds it up, says thanks. */
        private static ConversationScript thanksScript() {
            return new ConversationScript(List.of(
                    new ConversationScript.Line(true, ThoughtIcon.TOPIC_TOOLS),
                    new ConversationScript.Line(false, ThoughtIcon.EMOTE_CELEBRATION),
                    new ConversationScript.Line(false, ThoughtIcon.EMOTE_GRATITUDE)),
                    LINE_TICKS, List.of(0, 8, 6), List.of("enchanting"));
        }

        private ThoughtIcon thanksLine(Context ctx, NpcEntity worker, int index) {
            long until = ctx.level().getGameTime() + LINE_TICKS + 30;
            if (index == 0) holdTool(ctx, worker, ctx.npc(), toolNow(worker), LINE_TICKS + 20);
            if (index == 1) {
                ctx.npc().clearDisplayItem();
                worker.holdDisplayItem(toolNow(worker), until + LINE_TICKS);
            }
            return thanksScript().line(index).icon();
        }

        private ItemStack toolNow(NpcEntity worker) {
            return job.tool.stack(worker).copyWithCount(1);
        }

        private static void holdTool(Context ctx, NpcEntity worker, NpcEntity who, ItemStack tool, int ticks) {
            if (!tool.isEmpty()) who.holdDisplayItem(tool, ctx.level().getGameTime() + ticks);
        }
    }

    // =========================================================================
    // The worker's side
    // =========================================================================

    /** The worker's work session while a useful book waits: fetch it, find the smith, be served. */
    public static final class Visit extends WorkActivity {

        private enum Step { FETCH, APPROACH, WAIT }

        @Nullable private Pick pick;
        @Nullable private Helper helper;
        @Nullable private Job job;
        @Nullable private Activity sub;
        @Nullable private MoveActivity move;
        @Nullable private BlockPos moveTarget;
        private Step step = Step.FETCH;
        private int waited;
        private int repath;
        private long startedAt;
        /** Arrived while the smith served someone else: the wait clock restarts once. */
        private boolean inLine;
        /** Every spot was taken by the time the trip began. */
        private boolean noSpot;

        @Override
        protected void start(Context ctx) {
            long now = ctx.level().getGameTime();
            startedAt = now;
            pick = pickBook(ctx);
            helper = pick != null ? findHelper(ctx, spotsFor(pick)) : null;
            if (pick != null && helper != null
                    && !book(helper.model().npcId, ctx.model().npcId, now, spotsFor(pick))) {
                noSpot = true;
                return;
            }
            if (pick != null && helper != null) {
                if (!pick.carried()) RESERVED_BOOKS.put(ctx.model().npcId, new Reserved(pick.book(), now));
                diary(ctx, "FIT taking " + EnchantBooks.describe(pick.book()) + " to "
                        + EnchantNeeds.nameOf(helper.npc()) + " for my " + pick.tool().label());
                ctx.npc().setActivityLabel("book for " + pick.tool().label());
                step = pick.carried() ? Step.APPROACH : Step.FETCH;
                if (step == Step.FETCH) {
                    sub = new FetchExactBookActivity(pick.book(), "fetch_enchanted_book");
                }
            }
        }

        @Override
        protected Status doTick(Context ctx) {
            if (noSpot) {
                // Someone booked the last spot first: back to work, ask again later.
                diary(ctx, "FIT no free spot with " + EnchantNeeds.nameOf(helper.npc()) + ", back to work");
                retryAfter(ctx, retryLater(ctx));
                return Status.FAILED;
            }
            if (pick == null || helper == null) return fail(ctx, "no book or no smith after all");
            if (!helper.npc().isAlive() || helper.npc().isRemoved()) return fail(ctx, "the smith is gone");
            long tickNow = ctx.level().getGameTime();
            // Only the trip there is capped: a scene already under way is left to finish.
            if (step != Step.WAIT && tickNow - startedAt > MAX_TRIP_TICKS) return fail(ctx, "the trip took too long");
            // Still on the way: keep the spot. A long pause (a cleric's chat, a
            // serenade) can let it lapse; take it back only if it is still free.
            if (!keepSpot(helper.model().npcId, ctx.model().npcId, tickNow)) {
                return fail(ctx, "lost my spot while held up");
            }
            // A defender's errand never outlasts an alarm or a hit: the brain only
            // calls it to fight from outside a work session, and this is one.
            if (ctx.npc().getProfession().isDefender()
                    && (ctx.npc().hurtTime > 0 || GuardAlertSystem.hasEmergencyForGuard(ctx))) {
                return fail(ctx, "called away to fight");
            }

            if (step == Step.FETCH) {
                if (sub != null) {
                    Status status = sub.tickOuter(ctx);
                    if (status == Status.RUNNING) return Status.RUNNING;
                    sub.stop(ctx);
                    sub = null;
                    // In the pack now (or not to be had): nothing left to set aside.
                    RESERVED_BOOKS.remove(ctx.model().npcId);
                    if (status == Status.FAILED || !carries(ctx, pick.book())) {
                        return fail(ctx, "could not get the book from storage");
                    }
                }
                step = Step.APPROACH;
            }

            if (step == Step.APPROACH) {
                NpcEntity smith = helper.npc();
                if (ctx.npc().distanceToSqr(smith) <= TALK_RANGE_SQ
                        && Math.abs(ctx.npc().getY() - smith.getY()) < 1.0) {
                    stopMove(ctx);
                    Job existing = BY_HELPER.get(helper.model().npcId);
                    if (existing != null && !existing.over() && !existing.worker.equals(ctx.model().npcId)) {
                        // Someone else is being served: wait our turn.
                        return waitTurn(ctx, smith);
                    }
                    job = new Job(ctx.model().npcId, helper.model().npcId, pick.book(), pick.tool(),
                            helper.building(), helper.station(), helper.anvil());
                    job.workerSeen = ctx.level().getGameTime();
                    job.helperSeen = job.workerSeen;
                    ItemStack hand = ctx.npc().getMainHandItem();
                    job.workerHand = NpcEntity.isDisplayOnly(hand) ? ItemStack.EMPTY : hand.copy();
                    BY_HELPER.put(helper.model().npcId, job);
                    step = Step.WAIT;
                    waited = 0;
                    return Status.RUNNING;
                }
                if (++waited > MAX_WAIT_TICKS) return fail(ctx, "never caught up with the smith");
                follow(ctx, smith.blockPosition());
                return Status.RUNNING;
            }

            // Registered: follow the scene the smith runs.
            Job current = job;
            if (current == null) return fail(ctx, "lost the scene");
            long now = ctx.level().getGameTime();
            current.workerSeen = now;
            NpcEntity smith = helper.npc();
            switch (current.phase) {
                case ASKING -> {
                    stopMove(ctx);
                    look(ctx.npc(), smith);
                    if (++waited > MAX_WAIT_TICKS) return fail(ctx, "the smith never had time");
                    if (VillagerPause.stale(current.helper, current.helperSeen, MAX_WAIT_TICKS, now)) {
                        return fail(ctx, "the smith never came");
                    }
                }
                case TALKING, HANDING_BACK -> {
                    if (ctx.npc().distanceToSqr(smith) > TALK_RANGE_SQ) {
                        follow(ctx, smith.blockPosition());
                    } else {
                        stopMove(ctx);
                        ctx.npc().holdStillForAnimation();
                    }
                }
                case WORKING -> {
                    if (VillagerPause.stale(current.helper, current.helperSeen, STALE_TICKS, now)) {
                        return fail(ctx, "the smith stopped");
                    }
                    // Wait beside the anvil, watching.
                    if (ctx.npc().blockPosition().distSqr(current.station) > 9.0) {
                        BlockPos near = current.building.getWalkTargetNear(current.station,
                                ctx.mgr().getGraph(), ctx.npc().blockPosition(), 3);
                        follow(ctx, near != null ? near : smith.blockPosition());
                    } else {
                        stopMove(ctx);
                        look(ctx.npc(), smith);
                    }
                }
                case DONE -> {
                    release(ctx);
                    restoreHand(ctx);
                    retryAfter(ctx, RETRY_AFTER_DONE);
                    ctx.brain().remember(com.alex.test.village.social.MemoryTopic.WORK_DONE, ctx.level());
                    return Status.DONE;
                }
                case FAILED -> {
                    return fail(ctx, "the smith could not do it");
                }
            }
            return Status.RUNNING;
        }

        private Status waitTurn(Context ctx, NpcEntity smith) {
            look(ctx.npc(), smith);
            // The walk over used up some of the wait; a booked customer waits a full turn.
            if (!inLine) {
                inLine = true;
                waited = 0;
            }
            if (++waited > MAX_WAIT_TICKS) return fail(ctx, "the smith was busy with someone else");
            return Status.RUNNING;
        }

        private void follow(Context ctx, @Nullable BlockPos target) {
            if (target == null) return;
            if (move != null && moveTarget != null && moveTarget.distSqr(target) <= 4.0 && --repath > 0) {
                Status status = move.tickOuter(ctx);
                if (status == Status.RUNNING) return;
                stopMove(ctx);
                return;
            }
            stopMove(ctx);
            move = new MoveActivity(target, WALK_SPEED);
            moveTarget = target;
            repath = 40;
            move.tickOuter(ctx);
        }

        private void stopMove(Context ctx) {
            if (move != null) {
                move.stop(ctx);
                move = null;
                moveTarget = null;
                ctx.model().stopMoving();
            }
        }

        private static boolean carries(Context ctx, ItemStack book) {
            InventoryManager inv = ctx.npc().getInventory();
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                if (ItemStack.isSameItemSameComponents(inv.getSlot(i), book)) return true;
            }
            return false;
        }

        private Status fail(Context ctx, String why) {
            diary(ctx, "FIT gave up: " + why);
            retryAfter(ctx, retryLater(ctx));
            cancel(ctx);
            return Status.FAILED;
        }

        private void cancel(Context ctx) {
            if (job != null) {
                if (!job.over()) job.phase = Phase.FAILED;
                BY_HELPER.remove(job.helper, job);
            }
            release(ctx);
            restoreHand(ctx);
        }

        /** Frees the spot and the set-aside book. */
        private void release(Context ctx) {
            if (helper != null && !noSpot) unbook(helper.model().npcId, ctx.model().npcId);
            RESERVED_BOOKS.remove(ctx.model().npcId);
        }

        /** Puts back what the worker held before it held things up. */
        private void restoreHand(Context ctx) {
            NpcEntity npc = ctx.npc();
            npc.clearDisplayItem();
            if (job != null && !job.workerHand.isEmpty() && npc.getMainHandItem().isEmpty()) {
                npc.setItemSlot(EquipmentSlot.MAINHAND, job.workerHand.copy());
            }
        }

        /** Never: a chat struck up on the way would break the errand off. */
        @Override
        public boolean isMovingPhase() {
            return false;
        }

        @Override
        protected void doStop(Context ctx) {
            if (sub != null) { sub.stop(ctx); sub = null; }
            stopMove(ctx);
            if (job != null && job.phase != Phase.DONE) {
                retryAfter(ctx, retryLater(ctx));
            }
            cancel(ctx);
        }
    }

    // =========================================================================

    @Nullable
    private static NpcEntity workerEntity(Activity.Context ctx, Job job) {
        VillagerModel model = ctx.mgr().getModel(job.worker);
        NpcEntity npc = model != null ? model.puppet() : null;
        return npc != null && npc.isAlive() ? npc : null;
    }

    private static void look(NpcEntity npc, NpcEntity at) {
        npc.getLookControl().setLookAt(at, 30f, 30f);
    }

    private static void look(NpcEntity npc, BlockPos at) {
        npc.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5, 30f, 30f);
    }

    private static void diary(Activity.Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
