package com.alex.test.village.villagers.enchanter.behavior;

import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.enchanter.EnchanterTrance;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageDays;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.BookFitting;
import com.alex.test.village.villagers.enchanter.BookOrders;
import com.alex.test.village.villagers.enchanter.EnchantBooks;
import com.alex.test.village.villagers.enchanter.EnchantNeeds;
import com.alex.test.village.villagers.enchanter.EnchanterBalance;
import com.alex.test.village.villagers.enchanter.EnchanterSale;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Enchanter's working day: write the enchanted books the village's tools
 * need, at the library's enchanting table.
 *
 * <p>What to write comes from {@link EnchantNeeds}: the tools the workers
 * actually use, minus the books already waiting for them, most useful first.
 * When every tool is covered, or the day's books are done
 * ({@link EnchanterBalance#booksPerDay}, 2 to 5 by skill), it stops. Each book
 * is a small ritual rather than a factory tick: a while reading at one of the
 * library's shelves, then the table, with glyphs drawn in from the shelves,
 * for 40 seconds at level 1 down to 20 at level 100. A book costs a blank
 * book and 1 to 3 lapis by its level ({@link EnchanterBalance#lapisFor}), and
 * is as strong as the enchanter's skill and the library's shelves allow.
 *
 * <p>With no blank books in storage it makes its own when allowed: three
 * sugar cane pressed into paper, three paper and a leather bound into a book.
 * Whatever it still carries at the end of its work goes back to storage.
 *
 * <p>Each enchanter claims a table of its own for the session, so two never
 * crowd one. While the village is away it writes by the same rules at the away
 * pace: one shared {@code Offline} writer for both away calculators (the old
 * one's days, the new one's {@code EnchanterAway} day by day).
 */
public final class EnchanterActivity extends WorkActivity {

    /** Shelves further than this from the table send no glyphs; they would fly in from across the room. */
    private static final double GLYPH_RANGE_SQ = 8.0 * 8.0;
    /** Ticks between glyphs drawn into the table while it works. */
    private static final int GLYPH_INTERVAL = 3;
    /** Ticks between page turns while reading or working. */
    private static final int PAGE_SOUND_INTERVAL = 70;
    /** How long a wish list stays fresh: it reads every worker's pack and every chest. */
    private static final int PLAN_TTL_TICKS = 200;
    /** Books worth of materials fetched in one trip, at most. */
    private static final int MAX_BATCH = 3;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;

    private static final String TAG_BOOK_DAY = "EnchanterBookDay";
    private static final String TAG_BOOKS_TODAY = "EnchanterBooksToday";
    private static final String TAG_STATUS = "EnchanterStatus";
    private static final String TAG_SALE_RETRY = "EnchanterSaleRetryAt";
    /** Top books written to sell that may wait unsold in storage before it writes another. */
    private static final int MAX_UNSOLD = 2;
    /** A sale that fell through waits this long before the next try. */
    private static final int SALE_RETRY_TICKS = 20 * 60;

    private enum Step { STUDY, ENCHANT, PAPER, BIND }

    @Nullable private MoveActivity move;
    @Nullable private Activity sub;
    private boolean finishAfterSub;
    /** What happens when the walk ends, and then while {@link #workTimer} runs. */
    @Nullable private Step step;
    private int workTimer;
    /** Ticks into the book being written; a calmer ponder loop plays every {@link #PONDER_EVERY}. */
    private int writtenTicks;
    /** Ticks left of the clap after a book; the enchanter stands still for it. */
    private int finishTimer;
    private static final int PONDER_EVERY = 500;
    private static final int PONDER_TICKS = 73;
    private static final int FINISH_TICKS = 37;
    /** How often the trance's blindness is topped up, and how long each top-up lasts. */
    private static final int BLIND_INTERVAL = 10;
    private static final int BLIND_TICKS = 30;
    /** Players inside the trance (in at IN_RANGE, out past OUT_RANGE), for this book. */
    private final java.util.Set<UUID> entranced = new java.util.HashSet<>();
    /** The enchanting table this enchanter holds for the session. */
    @Nullable private BlockPos table;
    /** The shelf it reads at. */
    @Nullable private BlockPos shelf;
    @Nullable private EnchantNeeds.Wish wish;
    /** Read up for the next book already. */
    private boolean studied;
    private boolean workedThisSession;
    /** The trip under way only banks finished books: a failed one is no reason to stop. */
    private boolean bankingOnly;

    /** A wish list, when it was made, and the settings it was made under. */
    private record CachedPlan(long time, int settings, EnchantNeeds.Plan plan) {}

    /** Each enchanter's last wish list; brain scoring asks often and the list is costly. */
    private static final Map<UUID, CachedPlan> PLANS = new HashMap<>();

    /**
     * The book each enchanter is reading up for or writing now, so a second
     * enchanter in the village writes the next wish instead of the same one.
     */
    private static final Map<UUID, EnchantNeeds.Wish> WRITING = new HashMap<>();

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        // Bail out on the disabled switch only when nothing is in flight, so a
        // villager mid-walk or mid-book finishes cleanly.
        if (sub == null && move == null && workTimer == 0
                && !settings(ctx).getBool(EnchanterSettingsSchema.WORK_ENABLED)) {
            status(ctx, STATUS_OFF);
            return depositOrFinish(ctx);
        }

        // With no forge in the village, a worker brings its book here: served
        // between two steps; a walk under way is dropped and chosen again.
        if (sub == null && workTimer == 0 && BookFitting.hasCustomer(ctx)) {
            Activity serve = BookFitting.serve(ctx);
            if (serve != null) {
                if (move != null) {
                    move.stop(ctx);
                    move = null;
                    step = null;
                }
                sub = serve;
                finishAfterSub = false;
                workedThisSession = true;
                return Status.RUNNING;
            }
        }

        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            String subName = sub.name();
            sub = null;
            if (finishAfterSub) {
                finishAfterSub = false;
                return workedThisSession ? Status.DONE : Status.FAILED;
            }
            if ("fit_book".equals(subName)) return Status.RUNNING;
            if (bankingOnly) {
                bankingOnly = false;
                return Status.RUNNING;
            }
            if ("sell_book".equals(subName)) {
                if (status == Status.DONE) {
                    workedThisSession = true;
                } else {
                    ctx.npc().getBrainData().putLong(TAG_SALE_RETRY, ctx.level().getGameTime() + SALE_RETRY_TICKS);
                }
                PLANS.remove(ctx.model().npcId);
                return Status.RUNNING;
            }
            if (status == Status.FAILED) return depositOrFinish(ctx);
            return Status.RUNNING;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                if (step == Step.STUDY) {
                    // A shelf it cannot reach is no reason to skip the book.
                    diary(ctx, "ENCHANTER could not reach the shelf " + shelf + "; reading at the table");
                    studied = true;
                    step = null;
                    return Status.RUNNING;
                }
                diary(ctx, "ENCHANTER could not reach the table " + table);
                step = null;
                return depositOrFinish(ctx);
            }
            begin(ctx);
            return Status.RUNNING;
        }

        if (finishTimer > 0) {
            tickFinish(ctx);
            return Status.RUNNING;
        }

        if (workTimer > 0) {
            tickWork(ctx);
            return Status.RUNNING;
        }

        return chooseNext(ctx);
    }

    @Override
    public void doStop(Context ctx) {
        WRITING.remove(ctx.model().npcId);
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        workTimer = 0;
        finishTimer = 0;
        step = null;
        ctx.npc().setEnchanting(false);
        releaseTable(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    // =========================================================================
    // Choosing the next step
    // =========================================================================

    private Status chooseNext(Context ctx) {
        LibraryBuilding library = findLibrary(ctx);
        if (library == null) {
            status(ctx, "No library");
            return depositOrFinish(ctx);
        }
        if (!claimTable(ctx, library)) {
            status(ctx, library.getEnchantingTables().isEmpty()
                    ? "No enchanting table in the library" : "Every enchanting table is in use");
            return depositOrFinish(ctx);
        }
        ProfessionSettings settings = settings(ctx);
        boolean makeBooks = settings.getBool(EnchanterSettingsSchema.MAKE_BOOKS);
        int skill = ctx.brain().enchanterSkillLevel();
        // A merchant in the village and a top book nobody here needs: sold
        // first, as the merchant does not stay long.
        EnchanterSale sale = saleNow(ctx);
        if (sale != null) {
            status(ctx, "Selling " + sale.describe());
            sub = sale;
            finishAfterSub = false;
            return Status.RUNNING;
        }

        int left = booksLeftToday(ctx);
        EnchantNeeds.Plan plan = plan(ctx, true);
        EnchantNeeds.Wish next = plan.top();

        if (left <= 0) {
            // The day's books are written. Blank books for tomorrow's list, if
            // it wants more than storage holds, are the evening's work.
            if (next != null && makeBooks && blankBooksShort(ctx, plan, skill)) {
                Status making = makeBook(ctx, library, 1);
                if (making != null) {
                    status(ctx, "Binding books for tomorrow");
                    return making;
                }
            }
            status(ctx, STATUS_DONE + " (" + EnchanterBalance.booksPerDay(skill) + " books)");
            return depositOrFinish(ctx);
        }
        if (next == null) {
            status(ctx, idleReason(plan));
            return depositOrFinish(ctx);
        }
        wish = next;
        WRITING.put(ctx.model().npcId, next);
        int batch = Math.min(left, Math.min(MAX_BATCH, plan.wishes().size()));

        // A blank book to write in: from storage, or made.
        if (countItem(ctx, Items.BOOK) < 1) {
            int stored = storageFree(ctx, Items.BOOK);
            if (stored > 0) {
                sub = FetchFromStorageActivity.forItem(new ItemStack(Items.BOOK),
                        Math.min(stored, batch), "fetch_book");
                return Status.RUNNING;
            }
            if (makeBooks) {
                Status making = makeBook(ctx, library, batch);
                if (making != null) {
                    status(ctx, "Making a book to write in");
                    return making;
                }
            }
            status(ctx, makeBooks ? "No books, and no paper or leather to make one" : "No books in storage");
            return depositOrFinish(ctx);
        }

        // Lapis, leaving the player's reserve in storage.
        int lapis = EnchanterBalance.lapisFor(wish.bookLevel());
        int carried = countItem(ctx, Items.LAPIS_LAZULI);
        if (carried < lapis) {
            int reserve = settings.getInt(EnchanterSettingsSchema.RESERVE_LAPIS);
            int spare = spareStoredLapis(ctx, reserve);
            if (carried + spare < lapis) {
                status(ctx, reserve > 0 ? "Not enough lapis (keeping " + reserve + " for you)" : "Not enough lapis");
                return depositOrFinish(ctx);
            }
            sub = FetchFromStorageActivity.forItem(new ItemStack(Items.LAPIS_LAZULI),
                    Math.min(spare, Math.max(lapis, lapis * batch) - carried), "fetch_lapis");
            return Status.RUNNING;
        }

        if (!ctx.npc().getInventory().hasEmptySlot()) {
            // The finished book needs a slot of its own.
            sub = new DepositActivity();
            finishAfterSub = false;
            return Status.RUNNING;
        }

        status(ctx, "Writing " + wish.describe());
        if (!studied) {
            BlockPos reading = pickShelf(ctx, library);
            if (reading != null) {
                shelf = reading;
                return walkTo(ctx, library, reading, Step.STUDY);
            }
            studied = true;
        }
        return walkTo(ctx, library, table, Step.ENCHANT);
    }

    private static final String STATUS_NO_TOOLS = "No tools worth a book (iron or better)";
    private static final String STATUS_COVERED = "Every tool is covered";
    private static final String STATUS_DONE = "Done for today";
    private static final String STATUS_STRONGER = "Tools need stronger books (more skill or bookshelves)";
    private static final String STATUS_SETTINGS = "The books tools want are switched off in its settings";
    private static final String STATUS_OFF = "Switched off";

    /** Why nothing is wanted, in words a player understands. */
    private static String idleReason(EnchantNeeds.Plan plan) {
        if (plan.toolsSeen() == 0) return STATUS_NO_TOOLS;
        if (plan.blockedBySettings() > 0) return STATUS_SETTINGS;
        if (plan.blockedByCap() > 0) return STATUS_STRONGER;
        return STATUS_COVERED;
    }

    /**
     * Idle for a reason that is no fault: every tool covered, the day's books
     * written, tools waiting on more skill, or books the player switched off.
     * Not a complaint, so no "no work" bubble. Missing lapis, books, a table or
     * a library still is one: the player can fix those.
     */
    public static boolean restingContented(NpcEntity npc) {
        String status = status(npc);
        return status.equals(STATUS_COVERED) || status.equals(STATUS_NO_TOOLS) || status.startsWith(STATUS_DONE)
                || status.equals(STATUS_STRONGER) || status.equals(STATUS_SETTINGS);
    }

    /**
     * Steps toward one blank book: bind it, press paper, or fetch what is
     * short. Null when the materials are not there.
     */
    @Nullable
    private Status makeBook(Context ctx, LibraryBuilding library, int batch) {
        if (!canMakeBook(ctx)) return null;
        int paper = countItem(ctx, Items.PAPER);
        int leather = countItem(ctx, Items.LEATHER);
        int cane = countItem(ctx, Items.SUGAR_CANE);
        if (paper >= 3 && leather >= 1) return walkTo(ctx, library, table, Step.BIND);
        if (paper < 3) {
            int paperWanted = Math.max(3, 3 * batch) - paper;
            int storedPaper = storageFree(ctx, Items.PAPER);
            if (storedPaper >= 3 - paper) {
                sub = FetchFromStorageActivity.forItem(new ItemStack(Items.PAPER),
                        Math.min(storedPaper, paperWanted), "fetch_paper");
                return Status.RUNNING;
            }
            if (cane >= 3) return walkTo(ctx, library, table, Step.PAPER);
            int caneWanted = Math.ceilDiv(paperWanted, 3) * 3 - cane;
            sub = FetchFromStorageActivity.forItem(new ItemStack(Items.SUGAR_CANE),
                    Math.min(storageFree(ctx, Items.SUGAR_CANE), Math.max(3 - cane, caneWanted)),
                    "fetch_sugar_cane");
            return Status.RUNNING;
        }
        sub = FetchFromStorageActivity.forItem(new ItemStack(Items.LEATHER),
                Math.min(storageFree(ctx, Items.LEATHER), Math.max(1, batch - leather)), "fetch_leather");
        return Status.RUNNING;
    }

    /** Whether paper (or sugar cane for it) and a leather are within reach for one book. */
    private static boolean canMakeBook(Context ctx) {
        boolean leather = countItem(ctx, Items.LEATHER) >= 1 || storageFree(ctx, Items.LEATHER) >= 1;
        if (!leather) return false;
        int paper = countItem(ctx, Items.PAPER) + storageFree(ctx, Items.PAPER);
        int cane = countItem(ctx, Items.SUGAR_CANE) + storageFree(ctx, Items.SUGAR_CANE);
        return paper + cane / 3 * 3 >= 3;
    }

    /** Whether tomorrow's list wants more blank books than storage and the pack hold. */
    private static boolean blankBooksShort(Context ctx, EnchantNeeds.Plan plan, int skill) {
        int wanted = Math.min(plan.wishes().size(), EnchanterBalance.booksPerDay(skill));
        return countItem(ctx, Items.BOOK) + storageFree(ctx, Items.BOOK) < wanted;
    }

    private Status walkTo(Context ctx, LibraryBuilding library, @Nullable BlockPos target, Step next) {
        if (target == null) return depositOrFinish(ctx);
        BlockPos stand = next == Step.STUDY
                ? library.getWalkTargetNear(target, ctx.mgr().getGraph(), ctx.npc().blockPosition(), 2)
                : library.getWalkTargetBeside(target, ctx.mgr().getGraph(), ctx.npc().blockPosition());
        if (stand == null) {
            if (next == Step.STUDY) {
                studied = true;
                return Status.RUNNING;
            }
            diary(ctx, "ENCHANTER no place to stand beside " + target);
            return depositOrFinish(ctx);
        }
        step = next;
        // All the way onto the tile: "near" (2 blocks, or a tile off) was measured through walls, so it could
        // study or write outside the library (as the smith did outside the forge, 10-25).
        if (NavGraph.feetCell(ctx.npc()).equals(stand)) {
            begin(ctx);
        } else {
            ctx.npc().setActivityLabel("work");
            move = new MoveActivity(stand, WALK_SPEED, true);
        }
        return Status.RUNNING;
    }

    // =========================================================================
    // Working
    // =========================================================================

    private void begin(Context ctx) {
        if (step == null) return;
        int skill = ctx.brain().enchanterSkillLevel();
        NpcEntity npc = ctx.npc();
        ctx.model().stopMoving();
        switch (step) {
            case STUDY -> {
                workTimer = EnchanterBalance.studyTicks(skill);
                npc.setAnimation(VillagerAnimationState.READ);
                if (shelf != null) look(ctx, shelf);
                npc.setActivityLabel("study");
            }
            case ENCHANT -> {
                // The walk takes time: another trip could have used the book or lapis.
                if (wish == null || countItem(ctx, Items.BOOK) < 1
                        || countItem(ctx, Items.LAPIS_LAZULI) < EnchanterBalance.lapisFor(wish.bookLevel())
                        || !settings(ctx).isAllowed(EnchanterSettingsSchema.ALLOWED_ENCHANTMENTS, wish.kind().id())) {
                    step = null;
                    return;
                }
                workTimer = EnchanterBalance.tableTicks(skill, wish.bookLevel());
                npc.setAnimation(VillagerAnimationState.ENCHANT_WEAVE);
                writtenTicks = 0;
                entranced.clear();
                LibraryBuilding room = libraryWith(ctx, table);
                npc.setEnchantingRoom(room == null ? null : room.roomBox());
                npc.setEnchanting(true);
                look(ctx, table);
                npc.setActivityLabel("enchant " + EnchantBooks.name(wish.kind(), wish.bookLevel()));
                diary(ctx, "ENCHANTER writing " + wish.describe() + " at " + table.toShortString());
            }
            case PAPER -> {
                workTimer = EnchanterBalance.PAPER_TICKS;
                npc.setAnimation(VillagerAnimationState.CRAFTING);
                look(ctx, table);
                npc.setActivityLabel("make paper");
            }
            case BIND -> {
                workTimer = EnchanterBalance.BIND_TICKS;
                npc.setAnimation(VillagerAnimationState.CRAFTING);
                look(ctx, table);
                npc.setActivityLabel("bind book");
            }
        }
    }

    private void tickWork(Context ctx) {
        workTimer--;
        // Not stopMoving(): that also resets the pose to idle, every tick.
        ctx.npc().getNavigation().stop();
        if (step == Step.ENCHANT) {
            writtenTicks++;
            if (writtenTicks % BLIND_INTERVAL == 0) blindNearby(ctx);
            int phase = writtenTicks % PONDER_EVERY;
            if (writtenTicks >= PONDER_EVERY && phase == 0) {
                ctx.npc().setAnimation(VillagerAnimationState.ENCHANT_PONDER);
            } else if (writtenTicks >= PONDER_EVERY && phase == PONDER_TICKS) {
                ctx.npc().setAnimation(VillagerAnimationState.ENCHANT_WEAVE);
            }
        }
        if (step == Step.ENCHANT && table != null) {
            if (workTimer % GLYPH_INTERVAL == 0) drawGlyphs(ctx);
            look(ctx, table);
        } else if (step == Step.STUDY && shelf != null) {
            look(ctx, shelf);
        } else if (table != null) {
            look(ctx, table);
        }
        if (workTimer % PAGE_SOUND_INTERVAL == PAGE_SOUND_INTERVAL / 2
                && (step == Step.STUDY || step == Step.ENCHANT)) {
            ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.NEUTRAL, 0.6f, 0.9f + ctx.npc().getRandom().nextFloat() * 0.2f);
        }
        if (workTimer > 0) return;

        Step done = step;
        step = null;
        ctx.npc().setEnchanting(false);
        if (done == Step.ENCHANT) {
            // Everyone sees the clap; the glyph puff lands on it (tickFinish).
            ctx.npc().setAnimation(VillagerAnimationState.ENCHANT_FINISH);
            finishTimer = FINISH_TICKS;
        } else {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        if (done == null) return;
        switch (done) {
            case STUDY -> studied = true;
            case ENCHANT -> finishBook(ctx);
            case PAPER -> finishPaper(ctx);
            case BIND -> finishBinding(ctx);
        }
    }

    /**
     * The trance's darkness: players close to the writing enchanter get a
     * short Blindness, topped up while they stay, so it clears within about a
     * second once they step back past OUT_RANGE. Creative players too;
     * spectators see the trance without it.
     */
    private void blindNearby(Context ctx) {
        NpcEntity npc = ctx.npc();
        double out = EnchanterTrance.OUT_RANGE;
        for (net.minecraft.server.level.ServerPlayer player : ctx.level().players()) {
            UUID id = player.getUUID();
            double dist = player.distanceTo(npc);
            boolean near = player.isAlive() && !player.isSpectator()
                    && dist <= (entranced.contains(id) ? out : EnchanterTrance.IN_RANGE);
            if (!near) {
                entranced.remove(id);
                continue;
            }
            entranced.add(id);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.BLINDNESS, BLIND_TICKS, 0, true, false, false));
        }
    }

    /** The clap after a book: a puff of glyphs and the table's chime when the hands meet. */
    private void tickFinish(Context ctx) {
        finishTimer--;
        ctx.npc().getNavigation().stop();
        if (table != null) look(ctx, table);
        if (finishTimer == FINISH_TICKS - VillagerAnimationState.FINISH_CLAP_TICKS) {
            NpcEntity npc = ctx.npc();
            ctx.level().playSound(null, npc.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                    SoundSource.NEUTRAL, 0.8f, 1.0f);
            ctx.level().sendParticles(ParticleTypes.ENCHANT, npc.getX(), npc.getY() + 1.6, npc.getZ(),
                    40, 0.4, 0.4, 0.4, 0.6);
        }
        if (finishTimer == 0) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    /**
     * Glyphs drawn into the table from the library's shelves, the way a
     * vanilla table pulls them from the shelves around it while it works.
     */
    private void drawGlyphs(Context ctx) {
        LibraryBuilding library = findLibrary(ctx);
        if (library == null || table == null) return;
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : library.getBookshelves()) {
            if (pos.distSqr(table) <= GLYPH_RANGE_SQ) near.add(pos);
        }
        var random = ctx.npc().getRandom();
        double x = table.getX() + 0.5, y = table.getY() + 2.0, z = table.getZ() + 0.5;
        for (int i = 0; i < 2; i++) {
            if (near.isEmpty()) {
                ctx.level().sendParticles(ParticleTypes.ENCHANT, x, y - 1.0, z, 3, 0.4, 0.4, 0.4, 0.0);
                return;
            }
            BlockPos from = near.get(random.nextInt(near.size()));
            // Count 0: the offsets are the particle's velocity, which for the
            // enchant glyph means "start there and fly into the table".
            ctx.level().sendParticles(ParticleTypes.ENCHANT, x, y, z, 0,
                    from.getX() - table.getX() + random.nextFloat() - 0.5,
                    from.getY() - table.getY() - random.nextFloat() - 1.0,
                    from.getZ() - table.getZ() + random.nextFloat() - 0.5, 1.0);
        }
    }

    private void finishBook(Context ctx) {
        EnchantNeeds.Wish written = wish;
        WRITING.remove(ctx.model().npcId);
        wish = null;
        studied = false;
        if (written == null) return;
        int skill = ctx.brain().enchanterSkillLevel();
        int lapis = EnchanterBalance.lapisFor(written.bookLevel());
        if (countItem(ctx, Items.BOOK) < 1 || countItem(ctx, Items.LAPIS_LAZULI) < lapis) return;
        boolean saved = ctx.npc().getRandom().nextDouble() < EnchanterBalance.lapisSaveChance(skill);
        consumeItems(ctx, Items.BOOK, 1);
        if (!saved) consumeItems(ctx, Items.LAPIS_LAZULI, lapis);

        ItemStack book = EnchantBooks.makeBook(ctx.level(), written.kind(), written.bookLevel());
        book = VillageItemOwnership.produce(book, ctx.mgr());
        if (written.order() != null) {
            book = BookOrders.get(ctx.level().getServer()).onMade(ctx.mgr().getVillageId(), written.order(), book);
        }
        if (!ctx.npc().getInventory().addItem(book)) {
            com.alex.test.village.brain.ItemLossLog.drop(ctx.npc(), book, "enchanted book with a full pack");
        }
        countBook(ctx);
        workedThisSession = true;
        PLANS.remove(ctx.model().npcId);
        ctx.brain().onEnchanterExperience(ctx, EnchanterBalance.EXPERIENCE_PER_BOOK);

        if (table != null) {
            ctx.level().playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 0.8f, 1.0f);
            ctx.level().playSound(null, table, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.7f, 1.2f);
            ctx.level().sendParticles(ParticleTypes.ENCHANT, table.getX() + 0.5, table.getY() + 1.2,
                    table.getZ() + 0.5, 30, 0.5, 0.4, 0.5, 0.6);
        }
        diary(ctx, "ENCHANTER wrote " + written.describe() + (saved ? " (lapis saved)" : "")
                + " | today " + booksToday(ctx) + "/" + EnchanterBalance.booksPerDay(skill));

        if (settings(ctx).getBool(EnchanterSettingsSchema.AUTO_DEPOSIT)) {
            sub = DepositActivity.only(stack -> stack.is(Items.ENCHANTED_BOOK));
            finishAfterSub = false;
            bankingOnly = true;
        }
    }

    private void finishPaper(Context ctx) {
        if (countItem(ctx, Items.SUGAR_CANE) < 3) return;
        consumeItems(ctx, Items.SUGAR_CANE, 3);
        give(ctx, new ItemStack(Items.PAPER, 3));
        ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER,
                SoundSource.NEUTRAL, 0.7f, 1.0f);
        ctx.brain().onEnchanterExperience(ctx, EnchanterBalance.EXPERIENCE_PER_BINDING);
        workedThisSession = true;
        diary(ctx, "ENCHANTER pressed 3 sugar cane into paper");
    }

    private void finishBinding(Context ctx) {
        if (countItem(ctx, Items.PAPER) < 3 || countItem(ctx, Items.LEATHER) < 1) return;
        consumeItems(ctx, Items.PAPER, 3);
        consumeItems(ctx, Items.LEATHER, 1);
        give(ctx, new ItemStack(Items.BOOK));
        ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.VILLAGER_WORK_LIBRARIAN,
                SoundSource.NEUTRAL, 0.7f, 1.0f);
        ctx.brain().onEnchanterExperience(ctx, EnchanterBalance.EXPERIENCE_PER_BINDING);
        workedThisSession = true;
        diary(ctx, "ENCHANTER bound a book");
        if (booksLeftToday(ctx) <= 0) {
            // Bound for tomorrow: into storage now, not carried through the night.
            sub = DepositActivity.only(stack -> stack.is(Items.BOOK) || stack.is(Items.PAPER));
            finishAfterSub = false;
            bankingOnly = true;
        }
    }

    private static void give(Context ctx, ItemStack made) {
        ItemStack owned = VillageItemOwnership.produce(made, ctx.mgr());
        if (!ctx.npc().getInventory().addItem(owned)) {
            com.alex.test.village.brain.ItemLossLog.drop(ctx.npc(), owned, "enchanter craft with a full pack");
        }
    }

    private static void look(Context ctx, @Nullable BlockPos at) {
        if (at == null) return;
        ctx.npc().getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5, 30f, 30f);
    }

    /** Whatever it carries goes back to storage at the end of its work, books and leftovers alike. */
    private Status depositOrFinish(Context ctx) {
        releaseTable(ctx);
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = true;
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Table and shelves
    // =========================================================================

    /** Keeps this session's table, or takes a free one; false when there is none. */
    private boolean claimTable(Context ctx, LibraryBuilding library) {
        List<BlockPos> tables = library.getEnchantingTables();
        if (table != null && tables.contains(table)
                && ctx.mgr().getClaims().claim(table, ctx.model().npcId)) return true;
        releaseTable(ctx);
        for (BlockPos candidate : tables) {
            if (ctx.mgr().getClaims().claim(candidate, ctx.model().npcId)) {
                table = candidate;
                return true;
            }
        }
        return false;
    }

    private void releaseTable(Context ctx) {
        if (table == null) return;
        ctx.mgr().getClaims().release(table, ctx.model().npcId);
        table = null;
    }

    /** A shelf to read at: a random one, so it wanders the library a little. */
    @Nullable
    private static BlockPos pickShelf(Context ctx, LibraryBuilding library) {
        List<BlockPos> shelves = library.getBookshelves();
        if (shelves.isEmpty()) return null;
        return shelves.get(ctx.npc().getRandom().nextInt(shelves.size()));
    }

    // =========================================================================
    // The wish list and the day's count
    // =========================================================================

    /** This enchanter's wish list; {@code fresh} rebuilds it now instead of reusing a recent one. */
    static EnchantNeeds.Plan plan(Context ctx, boolean fresh) {
        return plan(ctx, fresh, null);
    }

    /** {@link #plan}, less the books {@code away} (the new away calculator) wrote for orders this catch-up. */
    private static EnchantNeeds.Plan plan(Context ctx, boolean fresh, @Nullable AwayBookOrders away) {
        long now = ctx.level().getGameTime();
        CachedPlan cached = PLANS.get(ctx.model().npcId);
        // A change in the settings screen counts at once, not after the cache runs out.
        int settingsHash = ctx.npc().getBrainData().getCompound(EnchanterSettingsSchema.SCHEMA.tagName()).hashCode();
        if (!fresh && cached != null && cached.settings() == settingsHash
                && now >= cached.time() && now - cached.time() < PLAN_TTL_TICKS) {
            return cached.plan();
        }
        LibraryBuilding library = findLibrary(ctx);
        int skill = ctx.brain().enchanterSkillLevel();
        int cap = EnchanterBalance.bookCap(skill, library != null ? library.bookshelfCount() : 0);
        ProfessionSettings settings = settings(ctx);
        EnchantNeeds.Plan plan = EnchantNeeds.plan(ctx.level(), ctx.mgr(), cap, skill,
                kind -> settings.isAllowed(EnchanterSettingsSchema.ALLOWED_ENCHANTMENTS, kind.id()),
                settings.getInt(EnchanterSettingsSchema.SPARE_BOOKS), orderWishes(ctx, cap, skill, settings, away));
        if (plan.wishes().isEmpty() && settings.getBool(EnchanterSettingsSchema.SELL_BOOKS)) {
            EnchantNeeds.Wish forSale = saleWish(ctx, cap, skill, settings);
            if (forSale != null) plan = new EnchantNeeds.Plan(List.of(forSale), plan.toolsSeen(), plan.blockedByCap(), plan.blockedBySettings());
        }
        plan = withoutOthersBooks(ctx, plan);
        PLANS.put(ctx.model().npcId, new CachedPlan(now, settingsHash, plan));
        return plan;
    }

    /**
     * The plan less the books other enchanters of the village are writing: one
     * wish each, matched by enchantment, worker and tool (or order).
     */
    private static EnchantNeeds.Plan withoutOthersBooks(Context ctx, EnchantNeeds.Plan plan) {
        List<EnchantNeeds.Wish> taken = new ArrayList<>();
        for (var entry : WRITING.entrySet()) {
            if (entry.getKey().equals(ctx.model().npcId) || ctx.mgr().getModel(entry.getKey()) == null) continue;
            taken.add(entry.getValue());
        }
        if (taken.isEmpty()) return plan;
        List<EnchantNeeds.Wish> open = new ArrayList<>();
        for (EnchantNeeds.Wish wish : plan.wishes()) {
            EnchantNeeds.Wish match = null;
            for (EnchantNeeds.Wish other : taken) {
                if (other.kind() == wish.kind() && java.util.Objects.equals(other.worker(), wish.worker())
                        && other.toolLabel().equals(wish.toolLabel())
                        && java.util.Objects.equals(other.order(), wish.order())) {
                    match = other;
                    break;
                }
            }
            if (match != null) taken.remove(match);
            else open.add(wish);
        }
        return new EnchantNeeds.Plan(open, plan.toolsSeen(), plan.blockedByCap(), plan.blockedBySettings());
    }

    /**
     * The player's Stock tab orders this enchanter can write now, one wish per
     * book still wanted, oldest order first. An order it cannot write yet
     * (not learned, library too small, switched off) waits. Away, the books
     * {@code away} counts for an order come off it (the order hears of them at the end).
     */
    private static List<EnchantNeeds.Wish> orderWishes(Context ctx, int cap, int skill, ProfessionSettings settings,
                                                       @Nullable AwayBookOrders away) {
        List<EnchantNeeds.Wish> wishes = new ArrayList<>();
        for (BookOrders.Want want : BookOrders.get(ctx.level().getServer()).wants(ctx.mgr().getVillageId())) {
            if (skill < want.kind().unlockLevel() || want.level() > cap
                    || !settings.isAllowed(EnchanterSettingsSchema.ALLOWED_ENCHANTMENTS, want.kind().id())) continue;
            int units = want.units() - (away == null ? 0 : away.booksAway(want.order()));
            for (int i = 0; i < units; i++) {
                wishes.add(EnchantNeeds.Wish.ordered(want.kind(), want.level(), want.by(), want.order()));
            }
        }
        return wishes;
    }

    /**
     * With every tool covered: a top-level book to sell, the most valuable this
     * enchanter can write at the top level in its library. Null while
     * {@link #MAX_UNSOLD} such books already wait for the merchant.
     */
    @Nullable
    private static EnchantNeeds.Wish saleWish(Context ctx, int cap, int skill, ProfessionSettings settings) {
        EnchantBooks.Kind best = null;
        for (EnchantBooks.Kind kind : EnchantBooks.KINDS) {
            if (skill < kind.unlockLevel() || kind.maxLevel() > cap
                    || !settings.isAllowed(EnchanterSettingsSchema.ALLOWED_ENCHANTMENTS, kind.id())) continue;
            if (best == null || EnchantBooks.price(kind) > EnchantBooks.price(best)) best = kind;
        }
        if (best == null || EnchanterSale.waitingForSale(ctx) >= MAX_UNSOLD) return null;
        return new EnchantNeeds.Wish(best, best.maxLevel(), 0, 0, null, "", EnchantNeeds.SALE_LABEL);
    }

    /** A sale to make now, when selling is on and the last one did not just fall through. */
    @Nullable
    private static EnchanterSale saleNow(Context ctx) {
        if (!settings(ctx).getBool(EnchanterSettingsSchema.SELL_BOOKS)) return null;
        if (ctx.level().getGameTime() < ctx.npc().getBrainData().getLong(TAG_SALE_RETRY)) return null;
        return EnchanterSale.next(ctx);
    }

    private static int booksToday(Context ctx) {
        return booksToday(ctx.npc(), ctx.level().getDayTime());
    }

    private static int booksToday(NpcEntity npc, long dayTime) {
        return booksWrittenOn(npc, VillageDays.dayIndex(dayTime));
    }

    /** Books {@code npc} wrote on village day {@code day} by its day stamp; 0 for any other day. */
    public static int booksWrittenOn(NpcEntity npc, long day) {
        CompoundTag data = npc.getBrainData();
        return data.contains(TAG_BOOK_DAY) && data.getLong(TAG_BOOK_DAY) == day ? data.getInt(TAG_BOOKS_TODAY) : 0;
    }

    /** Stamps {@code count} books as written on village day {@code day}, the stamp the day's limit reads. */
    public static void stampBooks(NpcEntity npc, long day, int count) {
        CompoundTag data = npc.getBrainData();
        data.putLong(TAG_BOOK_DAY, day);
        data.putInt(TAG_BOOKS_TODAY, count);
    }

    private static int booksLeftToday(Context ctx) {
        return booksLeftToday(ctx.npc(), ctx.brain().enchanterSkillLevel(), ctx.level().getDayTime());
    }

    /** Books this enchanter may still write today; 0 once the day's books are done (orders wait for tomorrow). */
    public static int booksLeftToday(NpcEntity npc, int skill, long dayTime) {
        return Math.max(0, EnchanterBalance.booksPerDay(skill) - booksToday(npc, dayTime));
    }

    private static void countBook(Context ctx) {
        stampBooks(ctx.npc(), VillageDays.dayIndex(ctx.level().getDayTime()), booksToday(ctx) + 1);
    }

    /** What the enchanter is doing or waiting for, in a few words; empty before its first work. */
    public static String status(NpcEntity npc) {
        return npc.getBrainData().getString(TAG_STATUS);
    }

    private static void status(Context ctx, String text) {
        CompoundTag data = ctx.npc().getBrainData();
        if (text.equals(data.getString(TAG_STATUS))) return;
        data.putString(TAG_STATUS, text);
        diary(ctx, "ENCHANTER status: " + text);
    }

    private static ProfessionSettings settings(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), EnchanterSettingsSchema.SCHEMA);
    }

    // =========================================================================
    // Inventory helpers
    // =========================================================================

    private static int countItem(Context ctx, Item item) {
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUse(stack) && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static boolean consumeItems(Context ctx, Item item, int amount) {
        InventoryManager inv = ctx.npc().getInventory();
        int remaining = amount;
        for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUse(stack) || stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /**
     * The library to work in: one with a table free for this enchanter first,
     * then one whose tables are all taken, then one with no table; among those,
     * the most bookshelves, since they set how strong a book can be.
     */
    @Nullable
    private static LibraryBuilding findLibrary(Context ctx) {
        LibraryBuilding best = null;
        int bestScore = -1;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof LibraryBuilding library) || !library.isValid()) continue;
            List<BlockPos> tables = library.getEnchantingTables();
            int score = library.bookshelfCount();
            if (!tables.isEmpty()) {
                boolean free = false;
                for (BlockPos pos : tables) {
                    if (!ctx.mgr().getClaims().isClaimedByOther(pos, ctx.model().npcId)) free = true;
                }
                score += free ? 20_000 : 10_000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = library;
            }
        }
        return best;
    }

    /** The library holding {@code table}, for the room its sounds are loud in. */
    @Nullable
    private static LibraryBuilding libraryWith(Context ctx, @Nullable BlockPos table) {
        if (table == null) return null;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof LibraryBuilding library && library.getEnchantingTables().contains(table)) {
                return library;
            }
        }
        return null;
    }

    /**
     * How many of {@code item} it may fetch: the chests it can reach, above
     * what the Stock tab holds back (as {@code FetchFromStorageActivity} takes).
     * Every live check asks this (leftovers round 6: before, they asked
     * {@link #storageAvailable} and promised what a fetch never got).
     */
    private static int storageFree(Context ctx, Item item) {
        int stored = storageAvailable(ctx, item);
        return stored <= 0 ? 0 : Math.min(stored, stockLeaves(ctx, item));
    }

    /** Stored lapis above the player's reserve and the Stock tab's holds (a fetch leaves both). */
    private static int spareStoredLapis(Context ctx, int reserve) {
        return Math.max(0, Math.min(storageAvailable(ctx, Items.LAPIS_LAZULI) - reserve,
                stockLeaves(ctx, Items.LAPIS_LAZULI)));
    }

    /** What the Stock tab leaves the enchanter of {@code item}: MAX_VALUE when it holds none back. */
    private static int stockLeaves(Context ctx, Item item) {
        return StockReserves.get(ctx.level().getServer()).free(ctx.mgr(), ctx.level(), item, Profession.ENCHANTER);
    }

    /** How many of {@code item} the village's storage holds in chests this villager can reach (Stock tab holds counted in). */
    private static int storageAvailable(Context ctx, Item item) {
        ItemStack wanted = new ItemStack(item);
        long now = ctx.level().getGameTime();
        int found = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            List<BlockPos> reachable = new ArrayList<>();
            for (BlockPos pos : storage.findChestsWithItem(ctx.level(), wanted)) {
                if (!ctx.brain().isChestUnreachable(pos, now)) reachable.add(pos);
            }
            for (var chest : StorageBuilding.inventories(ctx.level(), reachable)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.getItem() == item && storage.mayUse(ctx.level(), stack)) found += stack.getCount();
                }
            }
        }
        return found;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    // =========================================================================
    // Brain scoring pre-check
    // =========================================================================

    /**
     * Whether a work session would get anywhere: duty on, a library with a
     * free table, a book the village would gain from, books left today, and
     * a blank book and the lapis to write it (or paper and leather to bind
     * one). Leaves the reason in the enchanter's status when the answer is no.
     */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = settings(ctx);
        if (!settings.getBool(EnchanterSettingsSchema.WORK_ENABLED)) {
            // Switched off: it makes nothing, but what it carries (lapis, blank
            // books, finished books) still goes back to storage rather than
            // waiting in its pack until the player switches it on again.
            status(ctx, STATUS_OFF);
            return DepositActivity.countDepositable(ctx) > 0;
        }
        LibraryBuilding library = findLibrary(ctx);
        if (library == null) {
            status(ctx, "No library");
            return false;
        }
        boolean freeTable = false;
        for (BlockPos pos : library.getEnchantingTables()) {
            if (!ctx.mgr().getClaims().isClaimedByOther(pos, ctx.model().npcId)) freeTable = true;
        }
        if (!freeTable) {
            status(ctx, library.getEnchantingTables().isEmpty()
                    ? "No enchanting table in the library" : "Every enchanting table is in use");
            return false;
        }

        if (saleNow(ctx) != null) return true;
        EnchantNeeds.Plan plan = plan(ctx, false);
        EnchantNeeds.Wish top = plan.top();
        if (top == null) {
            status(ctx, idleReason(plan));
            return false;
        }
        boolean makeBooks = settings.getBool(EnchanterSettingsSchema.MAKE_BOOKS);
        int skill = ctx.brain().enchanterSkillLevel();
        if (booksLeftToday(ctx) <= 0) {
            boolean evening = makeBooks && blankBooksShort(ctx, plan, skill) && canMakeBook(ctx);
            if (!evening) status(ctx, STATUS_DONE + " (" + EnchanterBalance.booksPerDay(skill) + " books)");
            return evening;
        }
        boolean book = countItem(ctx, Items.BOOK) >= 1 || storageFree(ctx, Items.BOOK) >= 1
                || (makeBooks && canMakeBook(ctx));
        if (!book) {
            status(ctx, makeBooks ? "No books, and no paper or leather to make one" : "No books in storage");
            return false;
        }
        int reserve = settings.getInt(EnchanterSettingsSchema.RESERVE_LAPIS);
        int lapis = countItem(ctx, Items.LAPIS_LAZULI)
                + spareStoredLapis(ctx, reserve);
        if (lapis < EnchanterBalance.lapisFor(top.bookLevel())) {
            status(ctx, reserve > 0 ? "Not enough lapis (keeping " + reserve + " for you)" : "Not enough lapis");
            return false;
        }
        return true;
    }

    // =========================================================================
    // Offline catch-up
    // =========================================================================

    /**
     * What the enchanter wrote while nobody was near.
     *
     * @param made  enchanted books put into storage (or its pack, storage being full)
     * @param used  blank books, paper, sugar cane, leather and lapis taken
     * @param books books written
     * @param ticks work ticks the books took
     * @param stop  why it stopped before its books were done; null when it did not, or the time ran out
     */
    public record OfflineWork(Map<Item, Integer> made, Map<Item, Integer> used, int books, long ticks,
                              @Nullable String stop) {}

    /**
     * The player's book orders as the new away calculator keeps them
     * ({@code economy/away/AwayOrders}): the orders hear of the books only at its
     * WRITE, so a preview, or a run begun again after a restart, changes none.
     */
    public interface AwayBookOrders {
        /** Books written and marked for {@code order} earlier in this catch-up. */
        int booksAway(UUID order);

        /** One more book written and marked for {@code order}. */
        void bookWritten(UUID order);
    }

    /**
     * The away calculator's side of {@link #workOffline(Context, int, long, AwayHooks)}
     * ({@code economy/away/jobs/EnchanterAway}): it pays the practice ({@code learn}: no
     * level-up memory or diary line on the real villager), a storage trip takes
     * {@code tripTicks}, and the book orders wait for its WRITE.
     */
    public record AwayHooks(Consumer<Float> learn, long tripTicks, AwayBookOrders orders) {}

    /**
     * One away day: up to {@code books} books (the day's share,
     * worked out by {@code EnchanterAway}, which also stamps the day) within
     * {@code workTicks}, by the live rules ({@link Offline}).
     */
    public static OfflineWork workOffline(Context ctx, int books, long workTicks, AwayHooks hooks) {
        Offline work = new Offline(ctx, workTicks, hooks);
        String closed = work.closed();
        if (closed != null) return work.result(closed);
        work.write(books);
        PLANS.remove(ctx.model().npcId);
        return work.result(work.stop);
    }

    /** First storage room with space for the whole stack, or null. */
    @Nullable
    private static StorageBuilding roomFor(Context ctx, ItemStack stack) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && storage.canAcceptAll(ctx.level(), stack)) {
                return storage;
            }
        }
        return null;
    }

    /**
     * One away day's writing. Each book comes from a
     * fresh wish list ({@link #plan}: the workers' tools, the player's orders, spares,
     * a top book to sell); a blank book from the pack or storage, else bound from
     * three paper (or three sugar cane pressed) and a leather when allowed; lapis by
     * the book's level, the pack first, leaving the player's reserve in storage; the
     * lapis-save chance. Storage means the chests the enchanter can reach
     * ({@link #storageAvailable}), above what the Stock tab keeps for the player.
     * Everything is counted before anything is taken, and a part that still comes up
     * short is put back.
     */
    private static final class Offline {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        private static final String NO_MAKINGS = "No books, and no paper or leather to make one";

        private final Context ctx;
        private final AwayHooks hooks;
        private final boolean makeBooks;
        private final boolean storeAtOnce;
        private final int reserve;
        private final Map<Item, Integer> made = new java.util.LinkedHashMap<>();
        private final Map<Item, Integer> used = new java.util.LinkedHashMap<>();
        private long ticksLeft;
        private long ticksUsed;
        private int books;
        @Nullable private String stop;
        private boolean outOfTime;

        Offline(Context ctx, long workTicks, AwayHooks hooks) {
            this.ctx = ctx;
            this.hooks = hooks;
            ProfessionSettings settings = settings(ctx);
            this.makeBooks = settings.getBool(EnchanterSettingsSchema.MAKE_BOOKS);
            this.storeAtOnce = settings.getBool(EnchanterSettingsSchema.AUTO_DEPOSIT);
            this.reserve = settings.getInt(EnchanterSettingsSchema.RESERVE_LAPIS);
            this.ticksLeft = Math.max(0L, workTicks);
        }

        /** Why it cannot write at all (no library, no table in it); null when it can. */
        @Nullable
        String closed() {
            LibraryBuilding library = findLibrary(ctx);
            if (library == null) return "No library";
            if (library.getEnchantingTables().isEmpty()) return "No enchanting table in the library";
            return null;
        }

        boolean goesOn() {
            return stop == null && !outOfTime;
        }

        OfflineWork result(@Nullable String why) {
            return new OfflineWork(made, used, books, ticksUsed, why);
        }

        /** Up to {@code allowed} more books; how many were written. */
        int write(int allowed) {
            int written = 0;
            while (written < allowed && goesOn() && writeOne()) written++;
            return written;
        }

        /** One book; false with {@link #stop} or {@link #outOfTime} set. */
        private boolean writeOne() {
            PLANS.remove(ctx.model().npcId);
            EnchantNeeds.Plan plan = plan(ctx, true, hooks.orders());
            EnchantNeeds.Wish wish = plan.top();
            if (wish == null) {
                stop = idleReason(plan);
                return false;
            }
            int skill = ctx.brain().enchanterSkillLevel();
            long ticks = EnchanterBalance.studyTicks(skill) + EnchanterBalance.tableTicks(skill, wish.bookLevel());
            // A storage trip for each book stored at once, else one per batch of materials, as live fetches them.
            if (storeAtOnce || books % MAX_BATCH == 0) ticks += hooks.tripTicks();

            // A blank book: the pack, storage, or bound from paper (or sugar cane) and leather.
            boolean bind = have(Items.BOOK) < 1;
            boolean press = false;
            if (bind) {
                if (!makeBooks) {
                    stop = "No books in storage";
                    return false;
                }
                if (have(Items.LEATHER) < 1) {
                    stop = NO_MAKINGS;
                    return false;
                }
                if (have(Items.PAPER) < 3) {
                    if (have(Items.SUGAR_CANE) < 3) {
                        stop = NO_MAKINGS;
                        return false;
                    }
                    press = true;
                }
                ticks += EnchanterBalance.BIND_TICKS + (press ? EnchanterBalance.PAPER_TICKS : 0);
            }
            // Lapis, leaving the player's reserve in storage.
            int lapis = EnchanterBalance.lapisFor(wish.bookLevel());
            int spareLapis = spareLapis();
            if (countItem(ctx, Items.LAPIS_LAZULI) + spareLapis < lapis) {
                stop = reserve > 0 ? "Not enough lapis (keeping " + reserve + " for you)" : "Not enough lapis";
                return false;
            }
            if (ticks > ticksLeft) {
                outOfTime = true;
                return false;
            }
            // The finished book goes to storage, or the pack when storage is full, as live.
            StorageBuilding room = roomFor(ctx, new ItemStack(Items.ENCHANTED_BOOK));
            if (room == null && !ctx.npc().getInventory().hasEmptySlot()) {
                stop = "Storage and pack full";
                return false;
            }

            Taking taking = new Taking();
            boolean paid = bind
                    ? taking.take(press ? Items.SUGAR_CANE : Items.PAPER, 3, Integer.MAX_VALUE)
                            && taking.take(Items.LEATHER, 1, Integer.MAX_VALUE)
                    : taking.take(Items.BOOK, 1, Integer.MAX_VALUE);
            // The pack first, so what storage gives stays within the spare above the reserve.
            if (paid && ctx.npc().getRandom().nextDouble() >= EnchanterBalance.lapisSaveChance(skill)) {
                paid = taking.take(Items.LAPIS_LAZULI, lapis, spareLapis);
            }
            if (!paid) {
                taking.putBack();
                stop = "Materials ran short";
                return false;
            }
            taking.count();
            if (press) learn(EnchanterBalance.EXPERIENCE_PER_BINDING);
            if (bind) learn(EnchanterBalance.EXPERIENCE_PER_BINDING);

            ItemStack book = EnchantBooks.makeBook(ctx.level(), wish.kind(), wish.bookLevel());
            book = VillageItemOwnership.produce(book, ctx.mgr());
            if (wish.order() != null) book = forOrder(book, wish.order());
            if (room != null) room.depositItems(ctx.level(), book);
            keep(book, "enchanted book written away, nowhere to put it");
            made.merge(Items.ENCHANTED_BOOK, 1, Integer::sum);
            learn(EnchanterBalance.EXPERIENCE_PER_BOOK);
            ticksLeft -= ticks;
            ticksUsed += ticks;
            books++;
            return true;
        }

        /**
         * Marks a book written for the player's order, counted at the run's WRITE
         * ({@code AwayOrders}). A full or removed order leaves it the village's.
         */
        private ItemStack forOrder(ItemStack book, UUID order) {
            BookOrders orders = BookOrders.get(ctx.level().getServer());
            UUID village = ctx.mgr().getVillageId();
            ItemStack marked = orders.markAway(village, order, book, hooks.orders().booksAway(order));
            if (BookOrders.isOrdered(marked)) hooks.orders().bookWritten(order);
            return marked;
        }

        private void learn(float amount) {
            hooks.learn().accept(amount);
        }

        /** {@code item} it may use: its pack, and the storage it can reach above the Stock tab's holds. */
        private int have(Item item) {
            return countItem(ctx, item) + storedFree(item);
        }

        private int storedFree(Item item) {
            return storageFree(ctx, item);
        }

        private int spareLapis() {
            return spareStoredLapis(ctx, reserve);
        }

        private int stockFree(Item item) {
            return stockLeaves(ctx, item);
        }

        /** Into the pack, else storage; with no room anywhere, logged lost (nothing real changes away). */
        private void keep(ItemStack stack, String why) {
            if (stack.isEmpty() || ctx.npc().getInventory().addItem(stack)) return;
            store(stack);
            if (stack.isEmpty()) return;
            LOGGER.warn("[AwayEnchanter] {} lost {}x {}: {}", ctx.model().npcId.toString().substring(0, 8),
                    stack.getCount(), stack.getItem(), why);
        }

        /** Into any storage room that takes it; what does not fit stays in {@code stack}. */
        private void store(ItemStack stack) {
            for (Building building : ctx.mgr().getBuildings()) {
                if (stack.isEmpty()) return;
                if (building instanceof StorageBuilding storage && storage.isValid()) {
                    storage.depositItems(ctx.level(), stack);
                }
            }
        }

        /** What one book took, so a part that comes up short is put back where it came from. */
        private final class Taking {
            private final List<ItemStack> fromPack = new ArrayList<>();
            private final List<ItemStack> fromStorage = new ArrayList<>();

            /** {@code amount} of {@code item}: the pack first, then at most {@code storageCap} from storage. */
            boolean take(Item item, int amount, int storageCap) {
                int left = amount - takeFromPack(item, amount);
                if (left > 0) left -= takeStored(item, Math.min(left, storageCap));
                return left <= 0;
            }

            private int takeFromPack(Item item, int amount) {
                InventoryManager inv = ctx.npc().getInventory();
                int left = amount;
                for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (!inv.mayUse(stack) || stack.getItem() != item) continue;
                    int n = Math.min(left, stack.getCount());
                    fromPack.add(stack.copyWithCount(n));
                    inv.reduceSlot(i, n);
                    left -= n;
                }
                return amount - left;
            }

            /**
             * From the chests in reach, above the Stock tab's holds. One withdrawal hands
             * over one kind of stack (the village's own or unbound), so each chest is asked
             * until it gives nothing (S3).
             */
            private int takeStored(Item item, int amount) {
                int left = Math.min(amount, stockFree(item));
                int wanted = left;
                ItemStack sample = new ItemStack(item);
                long now = ctx.level().getGameTime();
                for (Building building : ctx.mgr().getBuildings()) {
                    if (left <= 0) break;
                    if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
                    for (BlockPos pos : storage.findChestsWithItem(ctx.level(), sample)) {
                        if (left <= 0) break;
                        if (ctx.brain().isChestUnreachable(pos, now)) continue;
                        while (left > 0) {
                            ItemStack got = storage.withdrawItemFromChest(ctx.level(), pos, sample, left);
                            if (got.isEmpty()) break;
                            left -= got.getCount();
                            fromStorage.add(got);
                        }
                    }
                }
                if (left < wanted) StockReserves.get(ctx.level().getServer()).forgetCounts();
                return wanted - left;
            }

            void putBack() {
                for (ItemStack stack : fromPack) keep(stack, "put back after a book it could not pay for");
                for (ItemStack stack : fromStorage) {
                    store(stack);
                    keep(stack, "put back after a book it could not pay for");
                }
                if (!fromStorage.isEmpty()) StockReserves.get(ctx.level().getServer()).forgetCounts();
            }

            void count() {
                for (ItemStack stack : fromPack) used.merge(stack.getItem(), stack.getCount(), Integer::sum);
                for (ItemStack stack : fromStorage) used.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
    }
}
