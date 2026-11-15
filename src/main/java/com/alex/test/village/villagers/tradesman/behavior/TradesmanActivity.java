package com.alex.test.village.villagers.tradesman.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcUiInteractionHandler;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.building.Building;
import com.alex.test.village.visitors.TravelerGatheringSpots;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.CarryReserve;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.tradesman.SellBox;
import com.alex.test.village.villagers.tradesman.TradesmanLedger;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSettingsSchema;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantOffer;
import com.alex.test.village.visitors.MerchantPricing;
import com.alex.test.village.visitors.MerchantTransactions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Tradesman sells the village's surplus to the visiting merchant, the same
 * trade a player makes at the merchant's screen, done automatically.
 *
 * <p>It works only while a merchant is in the village, and only sells goods on
 * that merchant's offer list, lot by lot at today's price (see
 * {@link MerchantPricing}): every lot sold lowers that good's next price, the
 * best-paying goods go first, and a good stops once its price falls under the
 * "Stop selling below" setting (sell box goods sell at any price). The haul is
 * sized to what sells before that floor. One round: empty the stall's sell box of whatever
 * the merchant wants (see {@link SellBox}), banking the rest of the village's
 * goods in it (not wanted today, or short of a lot), load the pack with as many lots of
 * surplus as fit (best-paying goods first, several kinds per trip, and across
 * as many chests as it takes), walk to the merchant, talk business over each
 * kind and get paid, take the sell box's emeralds back to its barrel, then go
 * back for more. Whatever is left when the goods or the merchant run out -- a
 * part lot, the emeralds -- goes to storage in one deposit.
 *
 * <p>Surplus is never sold below the reserves: {@link TradesmanSettingsSchema#RESERVE_KEEP}
 * per item, food per mouth, and the storage screen's stock limits. Sell box
 * goods have no reserve: a player chose to sell them.
 */
public final class TradesmanActivity extends WorkActivity {

    /** Ticks per line of the sales talk, the same pace as any villager conversation. */
    private static final int TALK_LINE_TICKS = 40;
    private static final int QUICK_LINE_TICKS = 28;
    private static final float QUICK_TRADE_CHANCE = 0.3f;
    /** Among longer wood trades, some include a hands-on inspection. */
    private static final float WOOD_INSPECTION_CHANCE = 0.45f;
    /** Saved per tradesman, keyed by merchant UUID and Minecraft day. */
    private static final String TAG_GREETINGS = "TradesmanGreetings";
    private static final int TRADESMAN_GREETED = 1;
    private static final int MERCHANT_GREETED = 2;
    /** Chance the merchant haggles over a good before agreeing (cosmetic: the price is fixed). */
    private static final float HAGGLE_CHANCE = 0.4f;
    /** Talking distance, flat: just under one block to two and a half, as villagers stand to chat. */
    private static final double TALK_MIN_DIST_SQ = 0.81;
    private static final double TALK_MAX_DIST_SQ = 6.25;
    private static final double TALK_MAX_HEIGHT = 0.9;
    /** Ticks the merchant keeps a good it just bought in its hand. */
    private static final int MERCHANT_HOLD_TICKS = 40;
    /** Empty pack slots kept free while loading, so the payment always has somewhere to go. */
    private static final int PAY_SLOTS = 2;
    /** Storage fetches per haul before the tradesman goes to sell what it has. */
    private static final int MAX_FETCHES_PER_HAUL = 16;
    /** Walks at the merchant (it strolls an open-air stall) before giving up on reaching it. */
    private static final int MAX_APPROACH_ATTEMPTS = 3;
    /** Walks at a sell box barrel before giving up on it. */
    private static final int MAX_BOX_ATTEMPTS = 2;
    /** How close the tradesman must stand to reach into a barrel. */
    private static final double BOX_REACH_SQ = 9.0;
    /**
     * Longest wait, loaded, for a merchant talking with another tradesman.
     */
    private static final int MAX_WAIT_TICKS = 4_800;
    /** Ticks a good is left alone after its fetch failed (unreachable chest, full pack). */
    private static final int NO_SALE_COOLDOWN_TICKS = 2_400;
    /** Ticks the whole job rests after a session that sold nothing. */
    private static final int MERCHANT_COOLDOWN_TICKS = 1_200;
    /** Ticks a merchant (or sell box) the tradesman could not reach is left alone. */
    private static final int UNREACHABLE_TICKS = 6_000;
    /** Ticks a hasWork answer is reused: it scans every storage chest. */
    private static final int HAS_WORK_CACHE_TICKS = 20;

    /** Per tradesman: item → game time it may be tried again. Transient; a reload clears it. */
    private static final Map<UUID, Map<Item, Long>> NO_SALE_UNTIL = new HashMap<>();
    /** Per tradesman: game time the job may start again. Transient. */
    private static final Map<UUID, Long> MERCHANT_BLOCKED_UNTIL = new HashMap<>();
    /** Per tradesman: a merchant it could not reach, and until when it leaves it alone. */
    private static final Map<UUID, Unreachable> MERCHANT_UNREACHABLE = new HashMap<>();
    /** Per tradesman: game time it may try the sell box again after failing to reach it. */
    private static final Map<UUID, Long> BOX_UNREACHABLE_UNTIL = new HashMap<>();
    /** Per tradesman: game time and answer of its last hasWork. */
    private static final Map<UUID, long[]> HAS_WORK_CACHE = new HashMap<>();
    /**
     * The tradesman's sell box notes, saved in its brain data so a reload keeps
     * them: {@code goods} (item id to units in its pack that came from the
     * box, kept apart from surplus so no reserve applies to them),
     * {@code owed} (emeralds in its pack that belong in the box), and
     * {@code touched} (game time they last changed).
     */
    private static final String TAG_SELL_BOX = "TradesmanSellBox";
    /**
     * Notes untouched this long are dropped. Every deposit leaves what they
     * describe in the pack (see {@link #sellBoxHoldings}), so they only go
     * stale if the goods were lost, and stale notes would send surplus
     * emeralds to the barrel. Two days covers a night and a missed workday.
     */
    private static final long BOX_NOTE_TICKS = 48_000L;

    private record Unreachable(UUID merchant, long until) {}

    private enum Phase { LOADING, BOX_TAKE, BOX_RETURN, TO_MERCHANT, WAITING, TALKING }

    private Phase phase = Phase.LOADING;
    @Nullable private Activity sub;
    @Nullable private MoveActivity move;
    private boolean finishAfterSub;
    private boolean soldThisSession;
    /** The good the running fetch is for; benched if the fetch fails. */
    @Nullable private Item fetchingItem;
    private int fetchesThisHaul;
    /**
     * Bank the pack before loading: at the start of a session and after every
     * round of sales. Leftovers (a part lot, goods the reserve now holds back,
     * the emeralds) go to storage then, instead of riding around all day.
     */
    private boolean bankBeforeLoading = true;
    private int approachAttempts;
    private int waitTicks;
    private boolean walkedToWaitSpot;

    /** The sell box has been looked at for this haul. */
    private boolean boxCheckedThisHaul;
    /** Units still to take from the sell box this haul, per item. */
    private final Map<Item, Integer> boxPlan = new LinkedHashMap<>();
    /**
     * Units still to take out of the sell box this haul and bank in storage,
     * per item: what will not sell today (see {@link #boxLeftovers}).
     */
    private final Map<Item, Integer> boxClearPlan = new LinkedHashMap<>();
    /** Units taken out of the sell box for storage on this haul's visit. */
    private int boxClearedThisTake;
    /** Barrels still to visit for {@link #boxPlan} and {@link #boxClearPlan}. */
    private final List<BlockPos> boxQueue = new ArrayList<>();
    @Nullable private BlockPos boxTarget;
    private int boxAttempts;
    /** The box return was started on the way out; finish the session after it. */
    private boolean finishAfterBoxReturn;

    private enum TalkStyle { QUICK, STANDARD, INSPECTION }

    /** A sales turn, its displayed good, and the quiet pause before it. */
    private record TalkLine(boolean merchantSpeaks, ThoughtIcon icon, @Nullable Item closes,
                            @Nullable Item shows, int pauseTicks) {
        TalkLine(boolean merchantSpeaks, ThoughtIcon icon, @Nullable Item closes) {
            this(merchantSpeaks, icon, closes, null, 0);
        }

        TalkLine(boolean merchantSpeaks, ThoughtIcon icon, @Nullable Item closes, @Nullable Item shows) {
            this(merchantSpeaks, icon, closes, shows, 0);
        }

        TalkLine withPause(int ticks) {
            return new TalkLine(merchantSpeaks, icon, closes, shows, ticks);
        }

        boolean inspectsGoods() {
            return merchantSpeaks && shows != null;
        }
    }

    @Nullable private List<TalkLine> talk;
    @Nullable private VisitorEntity talkingMerchant;
    @Nullable private ConverseActivity salesConversation;
    private int talkLineTicks = TALK_LINE_TICKS;
    /** A sale closed during the current talk; a talk that sold nothing is not repeated. */
    private boolean soldThisTalk;

    /**
     * One offer this tradesman may sell as surplus right now, with the stock
     * behind it and today's price in cents (the base moved by recent sales).
     * A lot is always half a stack.
     */
    private record Good(MerchantOffer offer, Item item, int price, int lot, int sellableUnits, int stored) {
        int carriedLots(int carried) { return Math.min(carried, sellableUnits) / lot; }
    }

    /** Today's price of {@code offer} for this village and merchant, in cents a stack. */
    private static int priceFor(Context ctx, VisitorEntity merchant, MerchantOffer offer) {
        return MerchantPricing.liveCents(ctx.level(), ctx.mgr(), merchant, offer);
    }

    /** Whether the merchant's purse covers one half-stack lot at {@code cents} a stack. */
    private static boolean purseCoversLot(Context ctx, VisitorEntity merchant, int cents) {
        return merchant.canPay(MerchantPricing.wouldPay(ctx.level(), ctx.mgr(), MerchantPricing.lotCents(cents)));
    }

    /**
     * Units of {@code item} that sell today before its price falls under the
     * floor setting. Counted in half stacks, the step the market memory moves
     * in; an odd price's whole-stack lot is two of them.
     */
    private static int unitsBeforeFloor(Context ctx, Item item, int basePrice) {
        int lots = MerchantPricing.lotsUntilFloor(ctx.level(), ctx.mgr(), item, basePrice, priceFloor(ctx));
        return lots * Math.max(1, new ItemStack(item).getMaxStackSize() / 2);
    }

    /** The "Stop selling below % of price" setting. */
    private static int priceFloor(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), TradesmanSettingsSchema.SCHEMA)
                .getInt(TradesmanSettingsSchema.PRICE_FLOOR);
    }

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            boolean fetchFailed = status == Status.FAILED && sub instanceof FetchFromStorageActivity;
            sub.stop(ctx);
            sub = null;
            // A session that only put things back is not work done: FAILED gives
            // the brain its work backoff and no work experience.
            if (finishAfterSub) return soldThisSession ? Status.DONE : Status.FAILED;
            if (fetchFailed && fetchingItem != null) benchItem(ctx, fetchingItem, "fetch failed");
            fetchingItem = null;
            return Status.RUNNING;
        }

        if (move != null) {
            if (phase == Phase.TO_MERCHANT) {
                VisitorEntity nearby = merchant(ctx);
                if (nearby != null && !busyWithAnother(ctx, nearby) && talkPlaced(ctx, nearby)) {
                    move.stop(ctx);
                    move = null;
                    return startTalk(ctx, nearby);
                }
            }
            // Once the visitor is ready, approach it immediately rather than
            // completing an obsolete walk to the waiting area first.
            if (phase == Phase.WAITING) {
                VisitorEntity ready = merchant(ctx);
                if (ready != null && !busyWithAnother(ctx, ready)) {
                    move.stop(ctx);
                    move = null;
                    phase = Phase.TO_MERCHANT;
                    approachAttempts = 0;
                    return Status.RUNNING;
                }
            }
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            // Arrived or not, the next step re-measures the distance.
            return Status.RUNNING;
        }

        return switch (phase) {
            case LOADING -> chooseNext(ctx);
            case BOX_TAKE -> tickBoxTake(ctx);
            case BOX_RETURN -> tickBoxReturn(ctx);
            case TO_MERCHANT -> approachMerchant(ctx);
            case WAITING -> tickWait(ctx);
            case TALKING -> tickTalk(ctx);
        };
    }

    @Override
    public void doStop(Context ctx) {
        if (talk != null) endTalk(ctx, merchant(ctx));
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    // =========================================================================
    // Loading
    // =========================================================================

    private Status chooseNext(Context ctx) {
        if (!workEnabled(ctx)) return depositOrFinish(ctx);
        VisitorEntity merchant = merchant(ctx);
        if (merchant == null) {
            // Bank what is left, then rest: if storage cannot take it, this
            // must not turn into a walk to the chests every few seconds.
            blockMerchant(ctx);
            return depositOrFinish(ctx);
        }

        List<Good> goods = goods(ctx, merchant);
        if (bankBeforeLoading) {
            bankBeforeLoading = false;
            // Already carrying something the merchant will buy (a talk that was
            // interrupted): sell it first and bank after that round, rather than
            // carrying the load back to storage only to fetch it again.
            if (talkItems(ctx, merchant, goods).isEmpty()) {
                if (boxReturnPending(ctx)) return startBoxReturn(ctx, false);
                if (DepositActivity.countDepositable(ctx) > 0) {
                    sub = new DepositActivity();
                    finishAfterSub = false;
                    return Status.RUNNING;
                }
            } else {
                phase = Phase.TO_MERCHANT;
                approachAttempts = 0;
                return Status.RUNNING;
            }
        }

        // The sell box first: the player put those goods out to be sold.
        if (!boxCheckedThisHaul) {
            boxCheckedThisHaul = true;
            if (startBoxTake(ctx, merchant)) return Status.RUNNING;
        }

        // Fill the pack before walking over: best-paying goods first, each
        // fetched to a whole number of lots. A fetch only empties one chest,
        // and what it brought counts towards the next, so a good spread over
        // several chests is simply fetched again from the next one.
        if (fetchesThisHaul < MAX_FETCHES_PER_HAUL) {
            for (Good good : goods) {
                if (good.stored() <= 0) continue;
                int carried = saleCount(ctx, good.item());
                int target = Math.min(good.sellableUnits(), carried + haulRoom(ctx, good.item()));
                target -= target % good.lot();
                if (target <= carried) continue;
                ItemStack wanted = new ItemStack(good.item());
                VillageTokenBinding.bind(wanted, ctx.mgr());
                fetchingItem = good.item();
                fetchesThisHaul++;
                sub = FetchFromStorageActivity.forItem(wanted, target - carried, "fetch_surplus");
                return Status.RUNNING;
            }
        }

        if (!talkItems(ctx, merchant, goods(ctx, merchant)).isEmpty()) {
            phase = Phase.TO_MERCHANT;
            approachAttempts = 0;
            return Status.RUNNING;
        }
        // Nothing left to sell: bank and rest, so a pack storage cannot take
        // does not bring the tradesman back to the chests every few seconds.
        if (!soldThisSession) blockMerchant(ctx);
        return depositOrFinish(ctx);
    }

    /**
     * Space for {@code item} in the pack, leaving {@link #PAY_SLOTS} empty
     * slots for the emeralds. Selling a lot frees at least as much room as its
     * payment takes, so those two slots are all the payment ever needs.
     */
    private static int haulRoom(Context ctx, Item item) {
        InventoryManager inv = ctx.npc().getInventory();
        ItemStack sample = new ItemStack(item);
        VillageTokenBinding.bind(sample, ctx.mgr());
        int max = sample.getMaxStackSize();
        int topUp = 0;
        int empty = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack slot = inv.getSlot(i);
            if (slot.isEmpty()) empty++;
            else if (ItemStack.isSameItemSameComponents(slot, sample))
                topUp += Math.max(0, slot.getMaxStackSize() - slot.getCount());
        }
        return topUp + Math.max(0, empty - PAY_SLOTS) * max;
    }

    // =========================================================================
    // Sell box
    // =========================================================================

    /**
     * Plans this haul's take from the sell box: whole lots of every good on the
     * merchant's list that sits in the stall's barrels with the village's ID,
     * and everything else of the village's in there that will not sell today
     * (see {@link #boxLeftovers}), which goes on to storage.
     *
     * @return true when there is something to take and the walk has started
     */
    private boolean startBoxTake(Context ctx, VisitorEntity merchant) {
        // Out of emeralds: the box is left as it is, not cleared into storage.
        if (boxBlocked(ctx) || merchant.purse() == 0) return false;
        MerchantStallBuilding stall = SellBox.stall(ctx.mgr());
        if (stall == null) return false;
        Map<Item, Integer> inBox = SellBox.goods(ctx.level(), ctx.mgr(), stall);
        if (inBox.isEmpty()) return false;

        boxPlan.clear();
        boxPlan.putAll(wantedFromBox(ctx, merchant, inBox));
        boxClearPlan.clear();
        boxClearPlan.putAll(boxLeftovers(ctx, merchant, inBox, boxPlan));
        if (boxPlan.isEmpty() && boxClearPlan.isEmpty()) return false;

        boxQueue.clear();
        for (BlockPos pos : accessibleBarrels(ctx, stall)) {
            Container box = SellBox.container(ctx.level(), pos);
            if (box != null && (holdsAny(ctx, box, boxPlan.keySet()) || holdsAny(ctx, box, boxClearPlan.keySet())))
                boxQueue.add(pos);
        }
        if (boxQueue.isEmpty()) {
            boxPlan.clear();
            boxClearPlan.clear();
            return false;
        }
        boxTarget = boxQueue.remove(0);
        boxAttempts = 0;
        boxClearedThisTake = 0;
        phase = Phase.BOX_TAKE;
        return true;
    }

    /** Units of each good in {@code inBox} the merchant's list takes today, in whole lots at today's price. */
    private static Map<Item, Integer> wantedFromBox(Context ctx, VisitorEntity merchant, Map<Item, Integer> inBox) {
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        for (MerchantOffer offer : merchant.getOffers()) {
            if (!MerchantPricing.isTradable(offer)) continue;
            Item item = MerchantCatalog.item(offer);
            int boxPrice = priceFor(ctx, merchant, offer);
            if (!purseCoversLot(ctx, merchant, boxPrice)) continue;
            int lot = MerchantPricing.lotSize(item);
            int units = inBox.getOrDefault(item, 0);
            if (units >= lot && !wanted.containsKey(item)) wanted.put(item, units - units % lot);
        }
        return wanted;
    }

    /**
     * The village's goods in the sell box that will not sell on this visit,
     * per item: whatever the merchant did not ask for today, and the part of a
     * wanted good short of a whole lot. They go to storage rather than wait in
     * the barrel. Never the barrel's emeralds, those are its takings, and only
     * goods a storage chest will take, so the pack does not fill up with
     * things there is nowhere to put.
     */
    private static Map<Item, Integer> boxLeftovers(Context ctx, VisitorEntity merchant, Map<Item, Integer> inBox,
                                                   Map<Item, Integer> wanted) {
        Map<Item, Integer> leftovers = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : inBox.entrySet()) {
            Item item = entry.getKey();
            if (item == Items.EMERALD) continue;
            // Asked for, but the purse cannot pay for it today: it stays in the box.
            if (!wanted.containsKey(item) && offerFor(merchant, item) != null) continue;
            int units = entry.getValue() - wanted.getOrDefault(item, 0);
            if (units > 0 && storageTakes(ctx, item)) leftovers.put(item, units);
        }
        return leftovers;
    }

    /** Some reachable storage chest of the village would take {@code item} right now. */
    private static boolean storageTakes(Context ctx, Item item) {
        ItemStack sample = new ItemStack(item);
        VillageTokenBinding.bind(sample, ctx.mgr());
        long now = ctx.level().getGameTime();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos pos : storage.getFunctionalBlocks()) {
                if (!ctx.brain().isChestUnreachable(pos, now) && storage.chestCanAccept(ctx.level(), pos, sample))
                    return true;
            }
        }
        return false;
    }

    private Status tickBoxTake(Context ctx) {
        VisitorEntity merchant = merchant(ctx);
        if (merchant == null) {
            resetBoxTake();
            return depositOrFinish(ctx);
        }
        if (boxTarget == null) return finishBoxTake(ctx);
        if (!nearBarrel(ctx, boxTarget)) {
            if (boxAttempts++ >= MAX_BOX_ATTEMPTS) {
                diary(ctx, "TRADESMAN could not reach the sell box at " + boxTarget.toShortString());
                blockBox(ctx);
                return finishBoxTake(ctx);
            }
            walkToBarrel(ctx, boxTarget);
            return Status.RUNNING;
        }

        Container box = SellBox.container(ctx.level(), boxTarget);
        if (box != null) {
            for (Map.Entry<Item, Integer> entry : boxPlan.entrySet()) {
                Item item = entry.getKey();
                int lot = lotFor(ctx, merchant, item);
                int room = haulRoom(ctx, item);
                int wanted = Math.min(entry.getValue(), room - room % lot);
                if (wanted <= 0) continue;
                int taken = SellBox.take(box, item, wanted, ctx.mgr(), ctx.npc().getInventory());
                if (taken <= 0) continue;
                entry.setValue(entry.getValue() - taken);
                addBoxGoods(ctx, item, taken);
                diary(ctx, "TRADESMAN took " + taken + "x " + item.getDescriptionId() + " from the sell box");
            }
            // After the lots, so a wanted good's whole lots stay with the sale
            // and only the odd units short of a lot go to storage.
            for (Map.Entry<Item, Integer> entry : boxClearPlan.entrySet()) {
                Item item = entry.getKey();
                int wanted = Math.min(entry.getValue(), haulRoom(ctx, item));
                if (wanted <= 0) continue;
                int taken = SellBox.take(box, item, wanted, ctx.mgr(), ctx.npc().getInventory());
                if (taken <= 0) continue;
                entry.setValue(entry.getValue() - taken);
                boxClearedThisTake += taken;
                diary(ctx, "TRADESMAN took " + taken + "x " + item.getDescriptionId()
                        + " from the sell box for storage ("
                        + (offerFor(merchant, item) != null ? "less than half a stack" : "not wanted today") + ")");
            }
            sparkle(ctx, boxTarget);
        }
        boxPlan.values().removeIf(left -> left <= 0);
        boxClearPlan.values().removeIf(left -> left <= 0);

        // On to the next barrel that still holds something planned.
        boxTarget = null;
        while (!boxQueue.isEmpty() && (!boxPlan.isEmpty() || !boxClearPlan.isEmpty())) {
            BlockPos next = boxQueue.remove(0);
            Container nextBox = SellBox.container(ctx.level(), next);
            if (nextBox != null && (holdsAny(ctx, nextBox, boxPlan.keySet())
                    || holdsAny(ctx, nextBox, boxClearPlan.keySet()))) {
                boxTarget = next;
                boxAttempts = 0;
                return Status.RUNNING;
            }
        }
        return finishBoxTake(ctx);
    }

    /**
     * Back to loading. Goods taken out of the sell box for storage are banked
     * at once, before the haul: carried along, they would take pack room from
     * the sale. The deposit leaves the sell box's lots in the pack (see
     * {@link #sellBoxHoldings}).
     */
    private Status finishBoxTake(Context ctx) {
        boolean cleared = boxClearedThisTake > 0;
        resetBoxTake();
        if (cleared && DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = false;
        }
        return Status.RUNNING;
    }

    private void resetBoxTake() {
        boxPlan.clear();
        boxClearPlan.clear();
        boxClearedThisTake = 0;
        boxQueue.clear();
        boxTarget = null;
        phase = Phase.LOADING;
    }

    /**
     * Takes the sell box's emeralds, and any of its goods that did not sell (a
     * part lot, or the merchant left), back to the stall's barrel. What does
     * not fit stays in the pack and is banked to storage with everything else.
     */
    private Status startBoxReturn(Context ctx, boolean finishing) {
        finishAfterBoxReturn = finishing;
        MerchantStallBuilding stall = SellBox.stall(ctx.mgr());
        List<BlockPos> barrels = stall == null ? List.of() : accessibleBarrels(ctx, stall);
        if (barrels.isEmpty() || boxBlocked(ctx)) {
            if (boxReturnPending(ctx)) diary(ctx, "TRADESMAN could not return to the sell box; banking its goods in storage");
            clearBox(ctx);
            return afterBoxReturn(ctx);
        }
        BlockPos nearest = barrels.get(0);
        for (BlockPos pos : barrels) {
            if (pos.distSqr(ctx.npc().blockPosition()) < nearest.distSqr(ctx.npc().blockPosition())) nearest = pos;
        }
        boxTarget = nearest;
        boxAttempts = 0;
        phase = Phase.BOX_RETURN;
        return Status.RUNNING;
    }

    private Status tickBoxReturn(Context ctx) {
        if (boxTarget == null) {
            clearBox(ctx);
            return afterBoxReturn(ctx);
        }
        if (!nearBarrel(ctx, boxTarget)) {
            if (boxAttempts++ >= MAX_BOX_ATTEMPTS) {
                diary(ctx, "TRADESMAN could not reach the sell box at " + boxTarget.toShortString()
                        + "; banking its goods in storage");
                blockBox(ctx);
                clearBox(ctx);
                return afterBoxReturn(ctx);
            }
            walkToBarrel(ctx, boxTarget);
            return Status.RUNNING;
        }

        MerchantStallBuilding stall = SellBox.stall(ctx.mgr());
        List<BlockPos> reachable = new ArrayList<>();
        reachable.add(boxTarget);
        if (stall != null) {
            for (BlockPos pos : SellBox.barrels(ctx.level(), stall)) {
                if (!pos.equals(boxTarget) && nearBarrel(ctx, pos)) reachable.add(pos);
            }
        }
        int emeralds = Math.min(boxOwed(ctx), countItem(ctx, Items.EMERALD));
        int returnedEmeralds = emeralds - putInBoxes(ctx, reachable, Items.EMERALD, emeralds);
        int returnedGoods = 0;
        for (Map.Entry<Item, Integer> entry : boxGoods(ctx).entrySet()) {
            int units = Math.min(entry.getValue(), countItem(ctx, entry.getKey()));
            returnedGoods += units - putInBoxes(ctx, reachable, entry.getKey(), units);
        }
        if (returnedEmeralds > 0) {
            diary(ctx, "TRADESMAN put " + returnedEmeralds + " emerald(s) in the sell box");
            ctx.level().playSound(null, boxTarget, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.5f, 1.1f);
        }
        if (returnedGoods > 0) diary(ctx, "TRADESMAN put " + returnedGoods + " unsold item(s) back in the sell box");
        if (returnedEmeralds < emeralds) diary(ctx, "TRADESMAN sell box full; banking the rest in storage");
        sparkle(ctx, boxTarget);
        clearBox(ctx);
        return afterBoxReturn(ctx);
    }

    private Status afterBoxReturn(Context ctx) {
        boxTarget = null;
        phase = Phase.LOADING;
        if (finishAfterBoxReturn) {
            finishAfterBoxReturn = false;
            return depositOrFinish(ctx);
        }
        // Now bank the rest (the surplus emeralds, part lots) before loading.
        bankBeforeLoading = true;
        return Status.RUNNING;
    }

    /**
     * Moves up to {@code amount} of {@code item} from the pack into the given
     * barrels, in order.
     *
     * @return how many did not fit and stayed in the pack
     */
    private static int putInBoxes(Context ctx, List<BlockPos> barrels, Item item, int amount) {
        int left = amount;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!stack.is(item) || !VillageItemOwnership.mayTrade(stack, ctx.mgr())) continue;
            int portion = Math.min(left, stack.getCount());
            ItemStack moving = stack.copyWithCount(portion);
            for (BlockPos pos : barrels) {
                Container box = SellBox.container(ctx.level(), pos);
                if (box != null) SellBox.insert(box, moving);
                if (moving.isEmpty()) break;
            }
            int moved = portion - moving.getCount();
            if (moved > 0) inv.reduceSlot(i, moved);
            left -= moved;
            if (!moving.isEmpty()) break; // every reachable barrel is full
        }
        return left;
    }

    private boolean holdsAny(Context ctx, Container box, Set<Item> items) {
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            ItemStack stack = box.getItem(slot);
            if (items.contains(stack.getItem()) && VillageItemOwnership.mayTrade(stack, ctx.mgr())) return true;
        }
        return false;
    }

    private static boolean nearBarrel(Context ctx, BlockPos pos) {
        return allowedStand(ctx, NavGraph.feetCell(ctx.npc()))
                && barrelWithinReach(ctx, ctx.npc().position(), ctx.npc().getEyePosition(), pos);
    }

    private void walkToBarrel(Context ctx, BlockPos barrel) {
        BlockPos stand = barrelStand(ctx, barrel);
        if (stand != null) move = stallMove(ctx, stand);
    }

    private static List<BlockPos> accessibleBarrels(Context ctx, MerchantStallBuilding stall) {
        List<BlockPos> accessible = new ArrayList<>();
        for (BlockPos barrel : SellBox.barrels(ctx.level(), stall)) {
            if (nearBarrel(ctx, barrel) || barrelStand(ctx, barrel) != null) accessible.add(barrel);
        }
        return accessible;
    }

    @Nullable
    private static BlockPos barrelStand(Context ctx, BlockPos barrel) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(barrel, 3, 2)) {
            if (!usableStand(ctx, node)) continue;
            Vec3 feet = new Vec3(node.x + 0.5, node.standY, node.z + 0.5);
            if (!barrelWithinReach(ctx, feet, feet.add(0, ctx.npc().getEyeHeight(), 0), barrel)) continue;
            double distance = feet.distanceToSqr(ctx.npc().position());
            if (distance < bestDistance && ctx.model().canReach(graph, node.feetPos())) {
                best = node.feetPos();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean barrelWithinReach(Context ctx, Vec3 feet, Vec3 eye, BlockPos barrel) {
        if (feet.distanceToSqr(Vec3.atCenterOf(barrel)) > BOX_REACH_SQ) return false;
        HitResult hit = ctx.level().clip(new ClipContext(eye, Vec3.atCenterOf(barrel),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.npc()));
        return hit.getType() == HitResult.Type.MISS
                || hit instanceof BlockHitResult block && block.getBlockPos().equals(barrel);
    }

    private static void sparkle(Context ctx, BlockPos barrel) {
        ctx.level().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                barrel.getX() + 0.5, barrel.getY() + 1.1, barrel.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.0);
    }

    private static Map<Item, Integer> boxGoods(Context ctx) {
        expireBoxNotes(ctx);
        return readBoxGoods(ctx.npc().getBrainData().getCompound(TAG_SELL_BOX));
    }

    private static Map<Item, Integer> readBoxGoods(CompoundTag notes) {
        CompoundTag saved = notes.getCompound("goods");
        Map<Item, Integer> goods = new HashMap<>();
        for (String id : saved.getAllKeys()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            if (item != null && item != Items.AIR && saved.getInt(id) > 0) goods.put(item, saved.getInt(id));
        }
        return goods;
    }

    /** Sell box units of {@code item} in the pack, never more than the pack holds. */
    private static int boxCarried(Context ctx, Item item) {
        return Math.min(boxGoods(ctx).getOrDefault(item, 0), countItem(ctx, item));
    }

    private static void addBoxGoods(Context ctx, Item item, int units) {
        CompoundTag notes = writeBoxNotes(ctx);
        if (!notes.contains("goods", Tag.TAG_COMPOUND)) notes.put("goods", new CompoundTag());
        CompoundTag goods = notes.getCompound("goods");
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        int now = goods.getInt(id) + units;
        if (now > 0) goods.putInt(id, now);
        else goods.remove(id);
        dropIfEmpty(ctx);
    }

    private static int boxOwed(Context ctx) {
        expireBoxNotes(ctx);
        return ctx.npc().getBrainData().getCompound(TAG_SELL_BOX).getInt("owed");
    }

    private static void addBoxOwed(Context ctx, int emeralds) {
        CompoundTag notes = writeBoxNotes(ctx);
        notes.putInt("owed", notes.getInt("owed") + emeralds);
    }

    /** The notes, created if missing, with {@code touched} set to now. */
    private static CompoundTag writeBoxNotes(Context ctx) {
        CompoundTag brain = ctx.npc().getBrainData();
        if (!brain.contains(TAG_SELL_BOX, Tag.TAG_COMPOUND)) brain.put(TAG_SELL_BOX, new CompoundTag());
        CompoundTag notes = brain.getCompound(TAG_SELL_BOX);
        notes.putLong("touched", ctx.level().getGameTime());
        return notes;
    }

    private static void dropIfEmpty(Context ctx) {
        CompoundTag notes = ctx.npc().getBrainData().getCompound(TAG_SELL_BOX);
        if (notes.getInt("owed") <= 0 && notes.getCompound("goods").isEmpty()) clearBox(ctx);
    }

    private static void clearBox(Context ctx) {
        ctx.npc().getBrainData().remove(TAG_SELL_BOX);
    }

    private static void expireBoxNotes(Context ctx) {
        CompoundTag notes = ctx.npc().getBrainData().getCompound(TAG_SELL_BOX);
        if (notes.contains("touched") && ctx.level().getGameTime() - notes.getLong("touched") > BOX_NOTE_TICKS) {
            clearBox(ctx);
        }
    }

    /**
     * What a tradesman carries for the sell box, per item: its goods and the
     * emeralds owed to the barrel. Deposits leave exactly this in the pack, so
     * the evening deposit cannot bank the barrel's emeralds into storage.
     */
    public static Map<Item, Integer> sellBoxHoldings(NpcEntity npc, long gameTime) {
        CompoundTag notes = npc.getBrainData().getCompound(TAG_SELL_BOX);
        if (notes.isEmpty() || gameTime - notes.getLong("touched") > BOX_NOTE_TICKS) return Map.of();
        Map<Item, Integer> holdings = readBoxGoods(notes);
        if (notes.getInt("owed") > 0) holdings.merge(Items.EMERALD, notes.getInt("owed"), Integer::sum);
        return holdings;
    }

    /** Sell box emeralds or goods are in the pack and belong back in the barrel. */
    private static boolean boxReturnPending(Context ctx) {
        if (Math.min(boxOwed(ctx), countItem(ctx, Items.EMERALD)) > 0) return true;
        for (Map.Entry<Item, Integer> entry : boxGoods(ctx).entrySet()) {
            if (Math.min(entry.getValue(), countItem(ctx, entry.getKey())) > 0) return true;
        }
        return false;
    }

    /**
     * Whether the sell box holds at least one whole lot of anything on the
     * merchant's list, or village goods that will not sell today and go to
     * storage (see {@link #boxLeftovers}).
     */
    private static boolean boxHasWork(Context ctx, VisitorEntity merchant) {
        if (boxBlocked(ctx) || merchant.purse() == 0) return false;
        MerchantStallBuilding stall = SellBox.stall(ctx.mgr());
        if (stall == null) return false;
        Map<Item, Integer> available = new HashMap<>();
        for (BlockPos barrel : accessibleBarrels(ctx, stall)) {
            Container box = SellBox.container(ctx.level(), barrel);
            if (box == null) continue;
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack stack = box.getItem(i);
                if (VillageItemOwnership.mayTrade(stack, ctx.mgr()))
                    available.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        if (available.isEmpty()) return false;
        Map<Item, Integer> wanted = wantedFromBox(ctx, merchant, available);
        return !wanted.isEmpty() || !boxLeftovers(ctx, merchant, available, wanted).isEmpty();
    }

    // =========================================================================
    // Walking to the merchant
    // =========================================================================

    private Status approachMerchant(Context ctx) {
        VisitorEntity merchant = merchant(ctx);
        if (merchant == null) return depositOrFinish(ctx);

        // Trade with the actual visitor, including one whose final stall step
        // has not completed. Its arrival flag is not a conversation position.
        if (busyWithAnother(ctx, merchant)) {
            phase = Phase.WAITING;
            waitTicks = 0;
            walkedToWaitSpot = false;
            return Status.RUNNING;
        }

        if (talk != null && talkingMerchant != merchant) endTalk(ctx, talkingMerchant);
        if (talkPlaced(ctx, merchant)) return startTalk(ctx, merchant);
        if (approachAttempts >= MAX_APPROACH_ATTEMPTS) {
            diary(ctx, "TRADESMAN could not reach the merchant at "
                    + merchant.blockPosition().toShortString());
            markUnreachable(ctx, merchant);
            return endKeepingHaul();
        }
        approachAttempts++;
        BlockPos spot = spotBeside(ctx, merchant);
        if (spot != null) move = stallMove(ctx, spot);
        return Status.RUNNING;
    }

    /** The merchant is talking business with another tradesman right now. */
    private static boolean busyWithAnother(Context ctx, VisitorEntity merchant) {
        NpcEntity partner = merchant.tradeTalkPartner(ctx.level().getGameTime());
        return partner != null && partner != ctx.npc();
    }

    /** Standing where two people talk: same level, a block or two apart. */
    private static boolean talkPlaced(Context ctx, VisitorEntity b) {
        NpcEntity a = ctx.npc();
        return allowedStand(ctx, NavGraph.feetCell(a)) && ConverseActivity.wellPlaced(a, b)
                && a.hasLineOfSight(b) && b.hasLineOfSight(a);
    }

    /**
     * A walkable cell one and a half to two and a half blocks from the
     * merchant, the nearest to the tradesman: close enough to talk, not so
     * close that the two stand in each other. Shared with the enchanter's book sale.
     */
    @Nullable
    public static BlockPos spotBeside(Context ctx, VisitorEntity merchant) {
        var graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos center = merchant.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(center, 3, 2)) {
            if (!usableStand(ctx, node)) continue;
            Vec3 feet = new Vec3(node.x + 0.5, node.standY, node.z + 0.5);
            if (Math.abs(feet.y - merchant.getY()) > TALK_MAX_HEIGHT) continue;
            double dx = feet.x - merchant.getX();
            double dz = feet.z - merchant.getZ();
            double flat = dx * dx + dz * dz;
            if (flat < TALK_MIN_DIST_SQ || flat > TALK_MAX_DIST_SQ) continue;
            if (!clearSight(ctx, feet.add(0, ctx.npc().getEyeHeight(), 0), merchant.getEyePosition())) continue;
            double dist = feet.distanceToSqr(ctx.npc().position());
            if (dist < bestDist && ctx.model().canReach(graph, node.feetPos())) {
                bestDist = dist;
                best = node.feetPos();
            }
        }
        return best;
    }

    private static boolean clearSight(Context ctx, Vec3 from, Vec3 to) {
        return ctx.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, ctx.npc())).getType() == HitResult.Type.MISS;
    }

    private static boolean allowedStand(Context ctx, BlockPos feet) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof MerchantStallBuilding stall && !stall.isOpenAir()
                    && !TravelerGatheringSpots.isExteriorStand(ctx.level(), feet, stall)) return false;
        }
        return true;
    }

    private static boolean usableStand(Context ctx, NavNode node) {
        return node.isRoutable() && !node.climb && allowedStand(ctx, node.feetPos())
                && NavGraph.hasStandingRoom(ctx.level(), node)
                && ctx.level().getEntities(ctx.npc(), ctx.npc().getBoundingBox().move(
                    node.x + 0.5 - ctx.npc().getX(), node.standY - ctx.npc().getY(),
                    node.z + 0.5 - ctx.npc().getZ()), other -> other.isAlive()).isEmpty();
    }

    /** A walk to {@code goal} that keeps out of the merchant's stall; shared with the enchanter's book sale. */
    public static MoveActivity stallMove(Context ctx, BlockPos goal) {
        Set<Long> excluded = new HashSet<>();
        NavGraph graph = ctx.mgr().getGraph();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof MerchantStallBuilding stall) || stall.isOpenAir()) continue;
            var bounds = stall.roomBox();
            BlockPos center = BlockPos.containing(bounds.getCenter());
            int radius = (int) Math.ceil(Math.max(bounds.getXsize(), bounds.getZsize()) / 2) + 1;
            int height = (int) Math.ceil(bounds.getYsize() / 2) + 1;
            if (graph != null) {
                for (NavNode node : graph.nodesInBox(center, radius, height)) {
                    if (stall.isInRoom(node.feetPos())) excluded.add(node.feetPos().asLong());
                }
            }
            excluded.add(stall.getDoorPos().asLong());
            excluded.add(stall.getDoorPos().above().asLong());
        }
        return new MoveActivity(goal, VillagerModel.WALK_SPEED, true, true, excluded);
    }

    private Status tickWait(Context ctx) {
        VisitorEntity merchant = merchant(ctx);
        if (merchant == null) return depositOrFinish(ctx);
        if (!busyWithAnother(ctx, merchant)) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            phase = Phase.TO_MERCHANT;
            approachAttempts = 0;
            return Status.RUNNING;
        }
        if (++waitTicks > MAX_WAIT_TICKS) {
            diary(ctx, "TRADESMAN gave up waiting for the merchant");
            markUnreachable(ctx, merchant);
            return endKeepingHaul();
        }
        if (!walkedToWaitSpot) {
            walkedToWaitSpot = true;
            BlockPos spot = waitSpot(ctx, merchant);
            if (spot != null && !NavGraph.feetCell(ctx.npc()).equals(spot)) {
                move = stallMove(ctx, spot);
                return Status.RUNNING;
            }
        }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (ctx.npc().hasLineOfSight(merchant))
            NpcUiInteractionHandler.faceEntity(ctx.npc(), merchant, true);
        return Status.RUNNING;
    }

    /** Wait off the entrance lane; open-air stalls may use their interior tiles. */
    @Nullable
    private static BlockPos waitSpot(Context ctx, VisitorEntity merchant) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos anchor = merchant.blockPosition();
        for (NavNode node : graph.nodesInBox(anchor, 5, 3)) {
            if (!usableStand(ctx, node) || !ctx.model().canReach(graph, node.feetPos())) continue;
            Vec3 feet = new Vec3(node.x + 0.5, node.standY, node.z + 0.5);
            if (Math.abs(feet.y - merchant.getY()) > TALK_MAX_HEIGHT
                    || !clearSight(ctx, feet.add(0, ctx.npc().getEyeHeight(), 0), merchant.getEyePosition())) continue;
            double distance = node.feetPos().distToCenterSqr(ctx.npc().position());
            if (distance < bestDistance) {
                best = node.feetPos();
                bestDistance = distance;
            }
        }
        return best;
    }

    // =========================================================================
    // Talking business
    // =========================================================================

    /**
     * Plans the talk for everything carried, as a conversation between the
     * two: a greeting the first time this pair meets each day, then for each
     * good the tradesman shows it and names its price. Quick trades skip the
     * questions; longer ones may include wood inspection or bargaining.
     * The sale of a good still closes on the merchant's "agree" line.
     */
    private Status startTalk(Context ctx, VisitorEntity merchant) {
        if (busyWithAnother(ctx, merchant) || !talkPlaced(ctx, merchant)) {
            phase = Phase.TO_MERCHANT;
            return Status.RUNNING;
        }
        if (talk != null && talkingMerchant == merchant) {
            // Replay only the current line's cosmetics after repositioning.
            // Its sale (if any) already happened and must never be paid twice.
            phase = Phase.TALKING;
            ctx.model().stopMoving();
            faceForTalk(ctx, merchant);
            merchant.holdForTradeTalk(ctx.npc(), ctx.level().getGameTime() + TALK_LINE_TICKS + 20);
            int index = salesConversation != null ? salesConversation.currentLine() : -1;
            if (index >= 0 && index < talk.size()) {
                showTalkGoods(ctx, merchant, index);
            }
            return Status.RUNNING;
        }
        List<Item> carried = talkItems(ctx, merchant, goods(ctx, merchant));
        if (carried.isEmpty()) {
            // Everything carried is sold: the sell box's emeralds back to its
            // barrel, the rest to storage, then the next haul.
            phase = Phase.LOADING;
            fetchesThisHaul = 0;
            boxCheckedThisHaul = false;
            if (boxReturnPending(ctx)) return startBoxReturn(ctx, false);
            bankBeforeLoading = true;
            return Status.RUNNING;
        }

        // Claim the merchant at once, so a second tradesman waits its turn.
        merchant.holdForTradeTalk(ctx.npc(), ctx.level().getGameTime() + TALK_LINE_TICKS + 20);
        talkingMerchant = merchant;
        var random = ctx.npc().getRandom();
        // Pick once for the whole exchange; interruptions keep this same script.
        TalkStyle style = TalkStyle.STANDARD;
        if (random.nextFloat() < QUICK_TRADE_CHANCE) {
            style = TalkStyle.QUICK;
        } else if (carried.stream().anyMatch(TradesmanActivity::isWood)
                && random.nextFloat() < WOOD_INSPECTION_CHANCE) {
            style = TalkStyle.INSPECTION;
        }
        talkLineTicks = style == TalkStyle.QUICK ? QUICK_LINE_TICKS : TALK_LINE_TICKS;
        talk = new ArrayList<>();
        int greeted = greetingsToday(ctx).getInt(merchant.getUUID().toString());
        if ((greeted & TRADESMAN_GREETED) == 0) {
            talk.add(new TalkLine(false, ThoughtIcon.EMOTE_GREETING, null));
        }
        if ((greeted & MERCHANT_GREETED) == 0) {
            talk.add(new TalkLine(true, ThoughtIcon.EMOTE_GREETING, null));
        }
        for (Item item : carried) {
            talk.add(new TalkLine(false, topicFor(item), null, item));
            if (style != TalkStyle.QUICK) {
                // The merchant borrows only a display copy. The real wood stays
                // in the tradesman's pack until the eventual agreement line.
                boolean inspect = style == TalkStyle.INSPECTION && isWood(item);
                talk.add(new TalkLine(true, ThoughtIcon.EMOTE_QUESTION, null, inspect ? item : null));
            }
            talk.add(new TalkLine(false, ThoughtIcon.TOPIC_TRADING, null));
            if (style != TalkStyle.QUICK && random.nextFloat() < HAGGLE_CHANCE)
                appendHaggling(talk, item, random);
            talk.add(new TalkLine(true, ThoughtIcon.EMOTE_AGREE, item));
        }
        talk.add(new TalkLine(false, ThoughtIcon.EMOTE_GRATITUDE, null));
        for (int i = 0; i < talk.size(); i++) {
            int pause = 0;
            if (i > 0) {
                if (style == TalkStyle.QUICK) pause = random.nextInt(7);
                else if (random.nextInt(3) != 0) pause = 4 + random.nextInt(15);
            }
            // Let the merchant hold the wood and think before the price reply.
            if (i > 0 && talk.get(i - 1).inspectsGoods()) pause += 20 + random.nextInt(21);
            talk.set(i, talk.get(i).withPause(pause));
        }
        soldThisTalk = false;
        ConversationScript script = new ConversationScript(talk.stream()
                .map(line -> new ConversationScript.Line(!line.merchantSpeaks(), line.icon())).toList(),
                talkLineTicks, talk.stream().map(TalkLine::pauseTicks).toList(), List.of("trading"));
        salesConversation = ConverseActivity.business(merchant, script,
                (lineCtx, index) -> beginSalesLine(lineCtx, merchant, index));
        phase = Phase.TALKING;
        ctx.model().stopMoving();
        faceForTalk(ctx, merchant);
        diary(ctx, "TRADESMAN talking business with the merchant: " + carried.size() + " good(s), "
                + style + ", " + script.startTick(script.size()) + " ticks");
        return Status.RUNNING;
    }

    private static boolean isWood(Item item) {
        ItemStack stack = new ItemStack(item);
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS);
    }

    /** Cosmetic bargaining: every branch still leads to the same priced agreement. */
    private static void appendHaggling(List<TalkLine> lines, Item item, RandomSource random) {
        switch (random.nextInt(3)) {
            case 0 -> {
                lines.add(new TalkLine(true, ThoughtIcon.EMOTE_DOUBT, null));
                lines.add(new TalkLine(false, ThoughtIcon.EMOTE_IDEA, null));
            }
            case 1 -> {
                lines.add(new TalkLine(true, ThoughtIcon.EMOTE_DISAGREE, null));
                lines.add(new TalkLine(false, ThoughtIcon.TOPIC_TRADING, null));
                lines.add(new TalkLine(true, ThoughtIcon.EMOTE_DOUBT, null));
                lines.add(new TalkLine(false, ThoughtIcon.EMOTE_IDEA, null));
            }
            default -> {
                lines.add(new TalkLine(true, ThoughtIcon.EMOTE_QUESTION, null));
                lines.add(new TalkLine(false, topicFor(item), null, item));
                lines.add(new TalkLine(true, ThoughtIcon.EMOTE_DOUBT, null));
                lines.add(new TalkLine(false, ThoughtIcon.EMOTE_PRIDE, null));
            }
        }
    }

    /** Keep a display copy visible through this turn and its following pause. */
    private void showTalkGoods(Context ctx, VisitorEntity merchant, int index) {
        TalkLine line = talk.get(index);
        if (line.shows() == null) return;
        int nextPause = index + 1 < talk.size() ? talk.get(index + 1).pauseTicks() : 0;
        NpcEntity holder = line.merchantSpeaks() ? merchant : ctx.npc();
        holdForTalk(ctx, holder, carriedCopy(ctx, line.shows()), talkLineTicks + nextPause + 20);
    }

    private Status tickTalk(Context ctx) {
        VisitorEntity merchant = merchant(ctx);
        if (merchant == null || talk == null || salesConversation == null || merchant != talkingMerchant) {
            endTalk(ctx, merchant);
            return depositOrFinish(ctx);
        }
        // Stop the exchange before another line or payment whenever the pair
        // loses its spacing, floor, visibility, or exclusive merchant claim.
        if (busyWithAnother(ctx, merchant) || !talkPlaced(ctx, merchant)) {
            salesConversation.pausePlayback();
            clearTalkPresentation(ctx, merchant);
            phase = Phase.TO_MERCHANT;
            approachAttempts = 0;
            return Status.RUNNING;
        }
        long now = ctx.level().getGameTime();
        merchant.holdForTradeTalk(ctx.npc(), now + TALK_LINE_TICKS + 20);
        ctx.npc().extendDisplayItem(now + 20);

        if (salesConversation.tickOuter(ctx) != Status.RUNNING) {
            endTalk(ctx, merchant);
            if (!soldThisTalk) {
                diary(ctx, "TRADESMAN talk with the merchant closed no sale; stopping");
                return depositOrFinish(ctx);
            }
            // Anything still carried (a good the reserve now holds back) waits
            // for the next haul; usually the pack is empty and this goes loading.
            return startTalk(ctx, merchant);
        }
        return Status.RUNNING;
    }

    /** The current day's greetings survive new work activities and entity reloads. */
    private static CompoundTag greetingsToday(Context ctx) {
        CompoundTag brainData = ctx.npc().getBrainData();
        CompoundTag greetings = brainData.getCompound(TAG_GREETINGS);
        long day = com.alex.test.village.VillageDays.dayIndex(ctx.level().getDayTime());
        if (!greetings.contains("Day") || greetings.getLong("Day") != day) {
            greetings = new CompoundTag();
            greetings.putLong("Day", day);
            brainData.put(TAG_GREETINGS, greetings);
        }
        return greetings;
    }

    /** Invoked once per script line; sales still close only on the agreement. */
    private ThoughtIcon beginSalesLine(Context ctx, VisitorEntity merchant, int index) {
        TalkLine line = talk.get(index);
        ThoughtIcon icon = line.icon();
        if (icon == ThoughtIcon.EMOTE_GREETING) {
            // Record the turn when it actually runs, so an interruption between
            // the hellos does not suppress the other participant's first wave.
            CompoundTag greetings = greetingsToday(ctx);
            String partner = merchant.getUUID().toString();
            int speaker = line.merchantSpeaks() ? MERCHANT_GREETED : TRADESMAN_GREETED;
            greetings.putInt(partner, greetings.getInt(partner) | speaker);
        }
        // Show the goods and the money: the tradesman holds up the good it
        // names; on the sale the emerald comes into its hand and the good goes
        // into the merchant's for a moment. Display copies only, never in a pack.
        if (index > 0 && talk.get(index - 1).inspectsGoods()) merchant.clearDisplayItem();
        showTalkGoods(ctx, merchant, index);
        if (line.closes() != null) {
            ItemStack good = carriedCopy(ctx, line.closes());
            if (completeSale(ctx, merchant, line.closes())) {
                soldThisTalk = true;
                holdForTalk(ctx, ctx.npc(), carriedCopy(ctx, Items.EMERALD), talkLineTicks + 20);
                holdForTalk(ctx, merchant, good, MERCHANT_HOLD_TICKS);
            } else {
                icon = ThoughtIcon.EMOTE_DISAGREE;
            }
        }
        return icon;
    }

    private static void faceForTalk(Context ctx, VisitorEntity merchant) {
        NpcUiInteractionHandler.faceEntity(ctx.npc(), merchant, true);
        NpcUiInteractionHandler.faceEntity(merchant, ctx.npc(), true);
    }

    /** Puts both bubbles and gestures away and lets the merchant go back to strolling. */
    private void endTalk(Context ctx, @Nullable VisitorEntity merchant) {
        // The original visitor can have departed or been replaced before cleanup.
        if (talkingMerchant != null) merchant = talkingMerchant;
        talk = null;
        talkingMerchant = null;
        salesConversation = null;
        clearTalkPresentation(ctx, merchant);
    }

    private void clearTalkPresentation(Context ctx, @Nullable VisitorEntity merchant) {
        ctx.npc().clearDisplayItem();
        ctx.npc().clearBubble();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (merchant != null && merchant.tradeTalkPartner(ctx.level().getGameTime()) == ctx.npc()) {
            merchant.clearBubble();
            merchant.clearDisplayItem();
            merchant.setAnimation(VillagerAnimationState.IDLE);
            merchant.holdForTradeTalk(ctx.npc(), 0L);
        }
    }

    /**
     * Puts a display copy in {@code who}'s hand. It never drops: the real
     * goods and emeralds stay in the pack (or are already the merchant's).
     */
    private static void holdForTalk(Context ctx, NpcEntity who, ItemStack display, int ticks) {
        if (display.isEmpty()) return;
        // Something real already in hand stays there; the prop is skipped.
        ItemStack held = who.getMainHandItem();
        if (!held.isEmpty() && !NpcEntity.isDisplayOnly(held)) return;
        who.holdDisplayItem(display, ctx.level().getGameTime() + ticks);
    }

    /** One of {@code item} copied from the pack, components and village binding included. */
    private static ItemStack carriedCopy(Context ctx, Item item) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.is(item) && VillageItemOwnership.mayTrade(stack, ctx.mgr())) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** The conversation topic a good belongs to, for the tradesman's opening line about it. */
    private static ThoughtIcon topicFor(Item item) {
        ItemStack stack = new ItemStack(item);
        if (stack.is(net.minecraft.tags.ItemTags.LOGS) || stack.is(net.minecraft.tags.ItemTags.PLANKS)
                || stack.is(net.minecraft.tags.ItemTags.SAPLINGS) || item == Items.APPLE || item == Items.STICK)
            return ThoughtIcon.TOPIC_WOOD;
        if (item == Items.EGG || item == Items.LEATHER || item == Items.FEATHER || item == Items.RABBIT_HIDE
                || stack.is(net.minecraft.tags.ItemTags.WOOL))
            return ThoughtIcon.TOPIC_LIVESTOCK;
        if (item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT
                || item == Items.PUMPKIN || item == Items.MELON_SLICE || item == Items.SUGAR_CANE)
            return ThoughtIcon.TOPIC_FARMING;
        if (item == Items.IRON_INGOT || item == Items.GOLD_INGOT || item == Items.COPPER_INGOT
                || item == Items.IRON_NUGGET)
            return ThoughtIcon.TOPIC_SMITHING;
        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.STONE
                || item == Items.COAL || item == Items.CHARCOAL || item == Items.RAW_IRON
                || item == Items.RAW_COPPER || item == Items.REDSTONE || item == Items.FLINT)
            return ThoughtIcon.TOPIC_MINING;
        if (item == Items.BOOK || item == Items.PAPER || item == Items.LAPIS_LAZULI || item == Items.BOOKSHELF)
            return ThoughtIcon.TOPIC_LEARNING;
        if (VillageItemOwnership.isVillagerFood(stack)) return ThoughtIcon.TOPIC_FOOD;
        return ThoughtIcon.TOPIC_STORAGE;
    }

    /**
     * Sells every whole lot of {@code item} carried, at the merchant's price:
     * the sell box's lots first (no reserve, their emeralds owed back to the
     * box), then surplus within what the reserves allow. A part lot stays
     * carried; it is topped up by the next haul, or returned or banked.
     */
    private boolean completeSale(Context ctx, VisitorEntity merchant, Item item) {
        MerchantOffer offer = offerFor(merchant, item);
        if (offer == null) {
            diary(ctx, "TRADESMAN could not close a sale of " + item.getDescriptionId() + " (not on the list)");
            return false;
        }
        int base = offer.fullPrice();
        int floor = priceFloor(ctx);
        int boxUnits = boxCarried(ctx, item);
        Good good = null;
        for (Good candidate : goods(ctx, merchant)) {
            if (candidate.item() == item) { good = candidate; break; }
        }
        int surplusUnits = good == null ? 0 : Math.min(saleCount(ctx, item), good.sellableUnits());

        // One lot at a time, each at today's price, exactly as a player
        // clicking the merchant screen would get: every lot sold lowers the
        // next one's price. The sell box's lots go first and sell at any
        // price (the player put them out); surplus stops at the floor.
        // Paid in whole emeralds; a half-lot's leftover half is saved as the
        // village's change and paid with the next one (MerchantPricing.payOut).
        // Players keep their own change, so neither eats the other's.
        int boxSold = 0, boxEarned = 0, surplusSold = 0, surplusEarned = 0;
        int boxCents = 0, surplusCents = 0;
        int lastCents = base * 100;
        int lot = MerchantPricing.lotSize(item);
        String stopped = null;
        while (true) {
            int cents = priceFor(ctx, merchant, offer);
            int worth = MerchantPricing.lotCents(cents);
            boolean fromBox = boxUnits - boxSold >= lot;
            if (!fromBox) {
                if (surplusUnits - surplusSold < lot) break;
                if (MerchantPricing.lotsUntilFloor(ctx.level(), ctx.mgr(), item, base, floor) <= 0) {
                    stopped = "price down to " + percentOf(cents, base) + "% of normal";
                    break;
                }
            }
            if (!merchant.canPay(MerchantPricing.wouldPay(ctx.level(), ctx.mgr(), worth))) {
                stopped = "the merchant is out of emeralds";
                com.alex.test.village.economy.MarketMemory.get(ctx.level().getServer())
                        .setRanDry(ctx.mgr().getVillageId(), true);
                break;
            }
            if (!consumeItems(ctx, item, lot)) break;
            int pay = MerchantPricing.payOut(ctx.level(), ctx.mgr(), worth);
            merchant.spend(pay);
            // The sale moves the price for everyone, player sales included.
            MerchantPricing.recordSale(ctx.level(), ctx.mgr(), item, lot, worth);
            lastCents = cents;
            if (fromBox) {
                boxSold += lot;
                boxEarned += pay;
                boxCents += worth;
            } else {
                surplusSold += lot;
                surplusEarned += pay;
                surplusCents += worth;
            }
        }
        if (boxSold + surplusSold <= 0) {
            diary(ctx, "TRADESMAN could not close a sale of " + item.getDescriptionId()
                    + " (" + (stopped != null ? stopped : countItem(ctx, item) + " carried") + ")");
            return false;
        }

        int emeralds = boxEarned + surplusEarned;
        for (int left = emeralds; left > 0; ) {
            int chunk = Math.min(left, Items.EMERALD.getDefaultMaxStackSize());
            ItemStack payment = VillageItemOwnership.produce(new ItemStack(Items.EMERALD, chunk), ctx.mgr());
            if (!ctx.npc().getInventory().addItem(payment)) ItemLossLog.drop(ctx, payment, "sale payment, pack full");
            left -= chunk;
        }
        ctx.level().playSound(null, merchant.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6f, 1.0f);

        long dayTime = ctx.level().getDayTime();
        int stack = new ItemStack(item).getMaxStackSize();
        if (boxSold > 0) {
            addBoxGoods(ctx, item, -boxSold);
            addBoxOwed(ctx, boxEarned);
            int percent = averagePercent(boxCents, boxSold, stack, base);
            diary(ctx, "TRADESMAN sold " + boxSold + "x " + item.getDescriptionId()
                    + " from the sell box, worth " + MerchantPricing.format(boxCents) + ", paid " + boxEarned
                    + " emerald(s) (" + percent + "% of normal)");
            TradesmanLedger.record(ctx.npc(), dayTime, item, boxSold, boxEarned, true, percent);
        }
        if (surplusSold > 0) {
            int percent = averagePercent(surplusCents, surplusSold, stack, base);
            diary(ctx, "TRADESMAN sold " + surplusSold + "x " + item.getDescriptionId()
                    + " to the merchant, worth " + MerchantPricing.format(surplusCents) + ", paid " + surplusEarned
                    + " emerald(s) (" + percent + "% of normal)");
            TradesmanLedger.record(ctx.npc(), dayTime, item, surplusSold, surplusEarned, false, percent);
        }
        if (stopped != null) {
            diary(ctx, "TRADESMAN stopped selling " + item.getDescriptionId() + ": " + stopped
                    + " (last lot at " + MerchantPricing.format(lastCents) + " a stack)");
        }
        soldThisSession = true;
        ctx.brain().remember(MemoryTopic.TRADED, ctx.level());
        return true;
    }

    /** A price in cents as a whole percentage of a base in emeralds. */
    private static int percentOf(int cents, int base) {
        return base <= 0 ? 100 : (int) Math.round(cents / (double) base);
    }

    /** What {@code units} were worth in cents, as a percentage of the base price per stack. */
    private static int averagePercent(int cents, int units, int stack, int base) {
        if (units <= 0 || base <= 0) return 100;
        return (int) Math.round(cents * (double) stack / ((double) units * base));
    }

    @Nullable
    private static MerchantOffer offerFor(VisitorEntity merchant, Item item) {
        for (MerchantOffer offer : merchant.getOffers()) {
            if (MerchantPricing.isTradable(offer) && MerchantCatalog.item(offer) == item) return offer;
        }
        return null;
    }

    /** Units in one lot of {@code item}: always half a stack. */
    private static int lotFor(Context ctx, VisitorEntity merchant, Item item) {
        return MerchantPricing.lotSize(item);
    }

    /**
     * Ends the session with the haul still in the pack. Used when the merchant
     * could not be reached: carrying the goods back to storage only to fetch
     * them again on the next try is the loop this class must not have. They
     * are banked by the next session (the merchant is left alone meanwhile)
     * or the evening deposit.
     */
    private Status endKeepingHaul() {
        phase = Phase.LOADING;
        return soldThisSession ? Status.DONE : Status.FAILED;
    }

    private Status depositOrFinish(Context ctx) {
        phase = Phase.LOADING;
        // The sell box's emeralds and unsold goods go back to its barrel first.
        if (boxReturnPending(ctx)) return startBoxReturn(ctx, true);
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = true;
            return Status.RUNNING;
        }
        return soldThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Goods
    // =========================================================================

    /**
     * The merchant's offers this tradesman may sell as surplus, each with at
     * least one lot to spare over its reserve (counting what is already
     * carried, which is still village stock), best-paying per pack slot first.
     */
    private static List<Good> goods(Context ctx, VisitorEntity merchant) {
        // A merchant with an empty purse buys nothing more today.
        if (merchant.purse() == 0) return List.of();
        Map<Item, Integer> stored = TradesmanSalePolicy.storedSaleItems(ctx.level(), ctx.mgr());
        List<Good> goods = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (MerchantOffer offer : merchant.getOffers()) {
            if (!MerchantPricing.isTradable(offer)) continue;
            Item item = MerchantCatalog.item(offer);
            if (!seen.add(item) || !salePermitted(ctx, item) || isBenched(ctx, item)) continue;
            int inStorage = stored.getOrDefault(item, 0);
            int units = inStorage + saleCount(ctx, item) - reserveFor(ctx, new ItemStack(item, 1));
            int price = priceFor(ctx, merchant, offer);
            int lot = MerchantPricing.lotSize(item);
            // Not even one lot left in the merchant's purse for this good.
            if (!purseCoversLot(ctx, merchant, price)) continue;
            // Only what sells before the price drops under the floor: the haul
            // is sized to the market, so the rest stays in storage for a later
            // visit instead of riding to the stall and back.
            units = Math.min(units, unitsBeforeFloor(ctx, item, offer.fullPrice()));
            if (units >= lot) goods.add(new Good(offer, item, price, lot, units, inStorage));
        }
        // A pack slot holds a full stack, so today's full-stack price is what a
        // slot earns; the bigger pile breaks ties. Recomputed on every call, so
        // the order follows the prices as sales move them.
        goods.sort(Comparator.comparingInt((Good g) -> -g.price())
                .thenComparingInt(g -> -g.sellableUnits()));
        return goods;
    }

    /** Goods carried right now with at least one whole lot to sell: sell box lots, then surplus. */
    private static List<Item> talkItems(Context ctx, VisitorEntity merchant, List<Good> goods) {
        List<Item> items = new ArrayList<>();
        for (MerchantOffer offer : merchant.getOffers()) {
            if (!MerchantPricing.isTradable(offer)) continue;
            Item item = MerchantCatalog.item(offer);
            if (!items.contains(item) && boxCarried(ctx, item) >= lotFor(ctx, merchant, item))
                items.add(item);
        }
        for (Good good : goods) {
            if (!items.contains(good.item()) && good.carriedLots(saleCount(ctx, good.item())) > 0)
                items.add(good.item());
        }
        return items;
    }

    // =========================================================================
    // Cooldowns
    // =========================================================================

    /**
     * Leaves {@code item} alone for a while after fetching it failed. Whatever
     * stopped it -- an unreachable chest, a full pack -- is likely still true a
     * moment later, and trying again at once is a fetch-and-put-back loop.
     */
    private static void benchItem(Context ctx, Item item, String why) {
        NO_SALE_UNTIL.computeIfAbsent(ctx.npc().getUUID(), ignored -> new HashMap<>())
                .put(item, ctx.level().getGameTime() + NO_SALE_COOLDOWN_TICKS);
        diary(ctx, "TRADESMAN " + why + " for " + item.getDescriptionId() + "; leaving it for "
                + NO_SALE_COOLDOWN_TICKS / 20 + " s");
    }

    private static boolean isBenched(Context ctx, Item item) {
        Map<Item, Long> benched = NO_SALE_UNTIL.get(ctx.npc().getUUID());
        if (benched == null) return false;
        long now = ctx.level().getGameTime();
        benched.values().removeIf(until -> until <= now);
        if (benched.isEmpty()) NO_SALE_UNTIL.remove(ctx.npc().getUUID());
        return benched.containsKey(item);
    }

    /** Rests the job after a session that sold nothing, so it does not restart at once. */
    private static void blockMerchant(Context ctx) {
        MERCHANT_BLOCKED_UNTIL.put(ctx.npc().getUUID(), ctx.level().getGameTime() + MERCHANT_COOLDOWN_TICKS);
        HAS_WORK_CACHE.remove(ctx.npc().getUUID());
    }

    private static boolean merchantBlocked(Context ctx) {
        Long until = MERCHANT_BLOCKED_UNTIL.get(ctx.npc().getUUID());
        if (until == null) return false;
        if (until > ctx.level().getGameTime()) return true;
        MERCHANT_BLOCKED_UNTIL.remove(ctx.npc().getUUID());
        return false;
    }

    /**
     * Leaves a merchant the tradesman could not reach (or that never opened)
     * alone for a while. Until then the tradesman treats it as gone: it banks
     * its load once and rests, instead of fetching, walking, failing and
     * banking again for the whole visit.
     */
    private static void markUnreachable(Context ctx, VisitorEntity merchant) {
        MERCHANT_UNREACHABLE.put(ctx.npc().getUUID(),
                new Unreachable(merchant.getUUID(), ctx.level().getGameTime() + UNREACHABLE_TICKS));
        HAS_WORK_CACHE.remove(ctx.npc().getUUID());
    }

    private static boolean isUnreachable(Context ctx, VisitorEntity merchant) {
        Unreachable mark = MERCHANT_UNREACHABLE.get(ctx.npc().getUUID());
        if (mark == null) return false;
        if (mark.until() <= ctx.level().getGameTime()) {
            MERCHANT_UNREACHABLE.remove(ctx.npc().getUUID());
            return false;
        }
        return mark.merchant().equals(merchant.getUUID());
    }

    private static void blockBox(Context ctx) {
        BOX_UNREACHABLE_UNTIL.put(ctx.npc().getUUID(), ctx.level().getGameTime() + UNREACHABLE_TICKS);
    }

    private static boolean boxBlocked(Context ctx) {
        Long until = BOX_UNREACHABLE_UNTIL.get(ctx.npc().getUUID());
        if (until == null) return false;
        if (until > ctx.level().getGameTime()) return true;
        BOX_UNREACHABLE_UNTIL.remove(ctx.npc().getUUID());
        return false;
    }

    // =========================================================================
    // Inventory helpers
    // =========================================================================

    private static int countItem(Context ctx, @Nullable Item item) {
        if (item == null) return 0;
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (VillageItemOwnership.mayTrade(stack, ctx.mgr()) && stack.getItem() == item)
                total += stack.getCount();
        }
        return total;
    }

    /**
     * Units of {@code item} carried as surplus for sale: what is carried, less
     * the sell box's goods and the personal food supply the tradesman eats
     * from. Counting that supply as goods sold its own meals and sent it to
     * storage hungry halfway through a talk with the merchant. Mirrors
     * DepositActivity's keep rule, so what is not for sale here is exactly what
     * a deposit would leave in the pack; only goods it could sell are counted
     * against it.
     */
    private static int saleCount(Context ctx, Item item) {
        int carried = countItem(ctx, item) - boxCarried(ctx, item);
        if (carried <= 0) return 0;
        if (!VillageItemOwnership.isVillagerFood(new ItemStack(item))) return carried;
        InventoryManager inv = ctx.npc().getInventory();
        int foodKept = 0;
        int keptOfItem = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!VillageItemOwnership.mayUse(stack, ctx.mgr())
                    || VillageItemOwnership.villagerFood(stack, ctx.npc()) == null
                    || ProfessionSupplyRules.isProfessionProduct(ctx.npc(), stack)) continue;
            int keep = CarryReserve.toKeep(stack.getCount(), foodKept, ProfessionSupplyRules.FOOD_TARGET);
            foodKept += keep;
            if (stack.getItem() == item && VillageItemOwnership.mayTrade(stack, ctx.mgr())) keptOfItem += keep;
        }
        return Math.max(0, carried - keptOfItem);
    }

    private static boolean consumeItems(Context ctx, Item item, int amount) {
        if (countItem(ctx, item) < amount) return false;
        InventoryManager inv = ctx.npc().getInventory();
        int remaining = amount;
        for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!VillageItemOwnership.mayTrade(stack, ctx.mgr()) || stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /** The visiting merchant while it is trading and this tradesman can reach it, or null. */
    @Nullable
    private static VisitorEntity merchant(Context ctx) {
        VisitorEntity merchant = MerchantTransactions.openMerchant(ctx.level(), ctx.mgr());
        return merchant == null || isUnreachable(ctx, merchant) ? null : merchant;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /**
     * Brain pre-check: a merchant is trading and there is something to sell
     * (surplus, sell box goods, or a load already carried), or something to
     * put away (the sell box's emeralds, leftovers). The answer is reused for
     * a second: it scans every storage chest.
     */
    public static boolean hasWork(Context ctx) {
        // The barrel's emeralds go back even while the job rests or is
        // switched off; the pack holds them for it until then.
        if (!workEnabled(ctx) || merchantBlocked(ctx)) return boxReturnPending(ctx) && !boxBlocked(ctx);
        UUID id = ctx.npc().getUUID();
        long now = ctx.level().getGameTime();
        long[] cached = HAS_WORK_CACHE.get(id);
        if (cached != null && cached[0] > now) return cached[1] != 0;

        boolean work;
        VisitorEntity merchant = merchant(ctx);
        if (boxReturnPending(ctx)) {
            work = true;
        } else if (merchant == null) {
            work = DepositActivity.countDepositable(ctx) > 0;
        } else {
            List<Good> goods = goods(ctx, merchant);
            work = !goods.isEmpty() || boxHasWork(ctx, merchant)
                    || !talkItems(ctx, merchant, goods).isEmpty()
                    || DepositActivity.countDepositable(ctx) > 0;
        }
        HAS_WORK_CACHE.put(id, new long[]{now + HAS_WORK_CACHE_TICKS, work ? 1 : 0});
        return work;
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private static boolean workEnabled(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), TradesmanSettingsSchema.SCHEMA)
                .getBool(TradesmanSettingsSchema.WORK_ENABLED);
    }

    private static boolean salePermitted(Context ctx, Item item) {
        if (!TradesmanSalePolicy.canSell(item) || !TradesmanSalePolicy.isAllowed(ctx.npc(), item)) return false;
        return !VillageItemOwnership.isVillagerFood(new ItemStack(item))
                || ProfessionSettings.of(ctx.npc(), TradesmanSettingsSchema.SCHEMA)
                        .getBool(TradesmanSettingsSchema.SELL_FOOD_SURPLUS);
    }

    private static int keepReserve(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), TradesmanSettingsSchema.SCHEMA)
                .getInt(TradesmanSettingsSchema.RESERVE_KEEP);
    }

    /**
     * How much of {@code item} storage must keep back from sale.
     *
     * <p>Food is not just another commodity: it is how long the village lives
     * if the farms fail. Its reserve therefore scales with the number of mouths
     * rather than sitting at a flat number, because "keep sixty-four" is three
     * days' dinner in a village of four and half a day's in a village of fifty.
     *
     * <p>On the bench a tradesman took a village from a +79-a-day larder to
     * +4 and its whole stock from 1444 items to 302, selling the food down to
     * about one day's supply — the flat floor gated *whether* it could sell
     * food but the amount sold was computed against the ordinary 32-unit
     * reserve, so the floor never actually held anything back.
     *
     * <p>The Sale items tab's own number for the item beats the Behaviour tab's.
     */
    private static int reserveFor(Context ctx, ItemStack sample) {
        // The storage screen's stock limit keeps dish ingredients back from sale
        // too, and so do the archers' quivers and arrow makings.
        int stock = StockReserves.get(ctx.level().getServer())
                .reservedAmount(ctx.mgr(), ctx.level(), sample.getItem())
                + com.alex.test.village.villagers.archer.ArcherSupplies.keptForArchers(ctx.mgr(), sample.getItem());
        int foodPerVillager = ProfessionSettings.of(ctx.npc(), TradesmanSettingsSchema.SCHEMA)
                .getInt(TradesmanSettingsSchema.FOOD_KEEP_PER_VILLAGER);
        return TradesmanSalePolicy.keptBack(VillageItemOwnership.isVillagerFood(sample),
                TradesmanSalePolicy.keepAmount(ctx.npc(), sample.getItem()), keepReserve(ctx),
                foodPerVillager, ctx.mgr().getVillagers().size(), stock);
    }
}
