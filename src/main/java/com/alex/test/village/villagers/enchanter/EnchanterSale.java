package com.alex.test.village.villagers.enchanter;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tradesman.behavior.TradesmanActivity;
import com.alex.test.village.visitors.MerchantTransactions;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The enchanter selling a top-level book to the visiting merchant.
 *
 * <p>The merchant buys only the best: books with every enchantment at its
 * highest level, such as Unbreaking III, Efficiency V or Mending, at
 * {@link EnchantBooks#price}. Only books no worker's tool would gain from go:
 * the village's own tools come first. The enchanter takes one from storage,
 * walks over, shows it, the merchant looks it over and agrees, and the
 * emeralds go to storage with the rest of the enchanter's things at the end
 * of its work.
 *
 * <p>This runs beside the tradesman's half-stack trade rather than through
 * it: a book is one of a kind, told apart by what it stores, which the
 * merchant's item lists cannot express.
 */
public final class EnchanterSale extends Activity {

    private static final int LINE_TICKS = 40;
    private static final double TALK_RANGE_SQ = 2.8 * 2.8;
    private static final int MAX_APPROACH_TICKS = 20 * 45;

    private enum Step { FETCH, APPROACH, TALK }

    private final ItemStack book;
    private final int price;
    @Nullable private Activity sub;
    @Nullable private MoveActivity move;
    @Nullable private ConverseActivity talk;
    @Nullable private VisitorEntity merchant;
    private Step step;
    private int approachTicks;
    private boolean sold;

    private EnchanterSale(ItemStack book, boolean carried) {
        this.book = book.copyWithCount(1);
        this.price = EnchantBooks.price(book);
        this.step = carried ? Step.APPROACH : Step.FETCH;
    }

    /** A sale to make now: a merchant trading, free, and a top book nobody here needs. Null otherwise. */
    @Nullable
    public static EnchanterSale next(Context ctx) {
        VisitorEntity merchant = MerchantTransactions.openMerchant(ctx.level(), ctx.mgr());
        if (merchant == null) return null;
        NpcEntity partner = merchant.tradeTalkPartner(ctx.level().getGameTime());
        if (partner != null && partner != ctx.npc()) return null;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (forSale(ctx, stack) && inv.mayUse(stack) && worthSelling(ctx, merchant, stack))
                return new EnchanterSale(stack, true);
        }
        if (!inv.hasEmptySlot()) return null;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(ctx.level())) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (storage.mayUse(ctx.level(), stack) && forSale(ctx, stack)
                            && worthSelling(ctx, merchant, stack)) {
                        return new EnchanterSale(stack, false);
                    }
                }
            }
        }
        return null;
    }

    /** Books sold lately lower the next one's price; under half of normal the spare is kept for later. */
    private static final int BOOK_FLOOR_PERCENT = 50;

    private static boolean worthSelling(Context ctx, VisitorEntity merchant, ItemStack book) {
        int base = EnchantBooks.price(book);
        if (base <= 0) return false;
        int cents = com.alex.test.village.visitors.MerchantPricing.liveBookCents(ctx.level(), ctx.mgr(), null, book);
        return !com.alex.test.village.economy.MarketMemory.underFloor(cents, base, BOOK_FLOOR_PERCENT)
                && merchant.canPay(com.alex.test.village.visitors.MerchantPricing.wouldPay(ctx.level(), ctx.mgr(), cents));
    }

    /**
     * A top-level book of the village's that no worker's tool would gain from,
     * and that is not one of the spares the "Spare books to keep" setting holds.
     */
    private static boolean forSale(Context ctx, ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK) && !BookOrders.isOrdered(stack) && EnchantBooks.price(stack) > 0
                && VillageItemOwnership.mayTrade(stack, ctx.mgr())
                && !EnchantNeeds.wantedByAnyTool(ctx.level(), ctx.mgr(), stack)
                && !keptAsSpare(ctx, stack);
    }

    /**
     * Whether selling {@code book} would leave fewer spares of one of its
     * enchantments than the settings keep: with 2 spares and 2 Unbreaking III
     * books, neither goes; with 3, one may.
     */
    private static boolean keptAsSpare(Context ctx, ItemStack book) {
        int spares = ProfessionSettings.of(ctx.npc(), EnchanterSettingsSchema.SCHEMA)
                .getInt(EnchanterSettingsSchema.SPARE_BOOKS);
        if (spares <= 0) return false;
        List<ItemStack> books = EnchantNeeds.existingBooks(ctx.level(), ctx.mgr());
        for (EnchantBooks.Kind kind : EnchantBooks.KINDS) {
            int level = EnchantBooks.storedLevel(book, kind);
            if (level <= 0) continue;
            int same = 0;
            for (ItemStack other : books) {
                if (EnchantBooks.storedLevel(other, kind) >= level) same++;
            }
            if (same <= spares) return true;
        }
        return false;
    }

    /**
     * Top-level books of the village's in storage and in the pack, wanted by a
     * tool or not: a book written to sell that a worker turns out to want is
     * still one written, so it holds back the next as much as an unsold one.
     */
    public static int waitingForSale(Context ctx) {
        int count = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (topBook(ctx, inv.getSlot(i))) count++;
        }
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(ctx.level())) {
                for (int i = 0; i < chest.size(); i++) {
                    if (topBook(ctx, chest.peek(i))) count++;
                }
            }
        }
        return count;
    }

    private static boolean topBook(Context ctx, ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK) && !BookOrders.isOrdered(stack) && EnchantBooks.price(stack) > 0
                && VillageItemOwnership.mayTrade(stack, ctx.mgr());
    }

    @Override
    public String name() { return "sell_book"; }

    @Override
    protected Status tick(Context ctx) {
        // "Selling Top Books" switched off: a sale not yet agreed is dropped.
        if (!sold && !ProfessionSettings.of(ctx.npc(), EnchanterSettingsSchema.SCHEMA)
                .getBool(EnchanterSettingsSchema.SELL_BOOKS)) {
            return Status.FAILED;
        }
        if (step == Step.FETCH) {
            if (sub == null) sub = new FetchExactBookActivity(book, "fetch_book_to_sell");
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            sub = null;
            if (status == Status.FAILED || bookSlot(ctx) < 0) return Status.FAILED;
            step = Step.APPROACH;
        }

        merchant = MerchantTransactions.openMerchant(ctx.level(), ctx.mgr());
        if (merchant == null) return Status.FAILED;
        long now = ctx.level().getGameTime();
        NpcEntity partner = merchant.tradeTalkPartner(now);
        if (partner != null && partner != ctx.npc()) {
            // The tradesman is at the stall: wait a little, then come back later.
            stopMove(ctx);
            return ++approachTicks > MAX_APPROACH_TICKS ? Status.FAILED : Status.RUNNING;
        }

        if (step == Step.APPROACH) {
            if (ctx.npc().distanceToSqr(merchant) <= TALK_RANGE_SQ
                    && ConverseActivity.wellPlaced(ctx.npc(), merchant) && ctx.npc().hasLineOfSight(merchant)) {
                stopMove(ctx);
                merchant.holdForTradeTalk(ctx.npc(), now + LINE_TICKS + 20);
                talk = ConverseActivity.business(merchant, script(), this::line);
                step = Step.TALK;
                ctx.npc().setActivityLabel("sell book");
                return Status.RUNNING;
            }
            if (++approachTicks > MAX_APPROACH_TICKS) return Status.FAILED;
            if (move == null) {
                // The tradesman's spot: beside the stall, outside it, in sight of the merchant.
                BlockPos spot = TradesmanActivity.spotBeside(ctx, merchant);
                if (spot == null) return Status.RUNNING;
                move = TradesmanActivity.stallMove(ctx, spot);
            }
            Status status = move.tickOuter(ctx);
            if (status != Status.RUNNING) stopMove(ctx);
            return Status.RUNNING;
        }

        // Talking: keep the merchant for us until the last line.
        merchant.holdForTradeTalk(ctx.npc(), now + LINE_TICKS + 20);
        if (talk != null && talk.tickOuter(ctx) == Status.RUNNING) return Status.RUNNING;
        if (talk != null) talk.stop(ctx);
        talk = null;
        return sold ? Status.DONE : Status.FAILED;
    }

    /** Enchanter: hello, the book, the price. Merchant: hello, a look, yes. */
    private static ConversationScript script() {
        return new ConversationScript(List.of(
                new ConversationScript.Line(true, ThoughtIcon.EMOTE_GREETING),
                new ConversationScript.Line(false, ThoughtIcon.EMOTE_GREETING),
                new ConversationScript.Line(true, ThoughtIcon.TOPIC_ENCHANTING),
                new ConversationScript.Line(false, ThoughtIcon.EMOTE_SURPRISED),
                new ConversationScript.Line(true, ThoughtIcon.TOPIC_TRADING),
                new ConversationScript.Line(false, ThoughtIcon.EMOTE_AGREE),
                new ConversationScript.Line(true, ThoughtIcon.EMOTE_GRATITUDE)),
                LINE_TICKS, List.of(0, 6, 8, 20, 8, 12, 6), List.of("trading"));
    }

    private ThoughtIcon line(Context ctx, int index) {
        long until = ctx.level().getGameTime() + LINE_TICKS + 30;
        VisitorEntity buyer = merchant;
        switch (index) {
            case 2 -> ctx.npc().holdDisplayItem(book, until);
            case 3 -> {
                // The merchant takes it for a look.
                ctx.npc().clearDisplayItem();
                if (buyer != null && (buyer.getMainHandItem().isEmpty()
                        || NpcEntity.isDisplayOnly(buyer.getMainHandItem()))) {
                    buyer.holdDisplayItem(book, until + 20);
                }
            }
            case 5 -> {
                if (buyer != null && completeSale(ctx, buyer)) {
                    ctx.npc().holdDisplayItem(new ItemStack(Items.EMERALD), until);
                } else {
                    return ThoughtIcon.EMOTE_DISAGREE;
                }
            }
            default -> { }
        }
        return script().line(index).icon();
    }

    private boolean completeSale(Context ctx, VisitorEntity buyer) {
        int slot = bookSlot(ctx);
        // Today's price in cents: the base moved by the books sold lately
        // (MarketMemory); every book sold lowers the next one's.
        int cents = com.alex.test.village.visitors.MerchantPricing.liveBookCents(ctx.level(), ctx.mgr(), buyer, book);
        if (slot < 0 || cents <= 0
                || !buyer.canPay(com.alex.test.village.visitors.MerchantPricing.wouldPay(ctx.level(), ctx.mgr(), cents)))
            return false;
        ctx.npc().getInventory().reduceSlot(slot, 1);
        int paid = com.alex.test.village.visitors.MerchantPricing.payOut(ctx.level(), ctx.mgr(), cents);
        buyer.spend(paid);
        com.alex.test.village.visitors.MerchantPricing.recordBookSale(ctx.level(), ctx.mgr(), book, cents);
        if (paid > 0) {
            ItemStack payment = VillageItemOwnership.produce(new ItemStack(Items.EMERALD, paid), ctx.mgr());
            if (!ctx.npc().getInventory().addItem(payment)) ItemLossLog.drop(ctx, payment, "book sale payment, pack full");
        }
        ctx.level().playSound(null, buyer.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6f, 1.0f);
        ctx.brain().remember(MemoryTopic.TRADED, ctx.level());
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " ENCHANTER sold " + EnchantBooks.describe(book)
                + " to the merchant, worth " + com.alex.test.village.visitors.MerchantPricing.format(cents)
                + ", paid " + paid + " emerald(s)");
        sold = true;
        return true;
    }

    private int bookSlot(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (ItemStack.isSameItemSameComponents(inv.getSlot(i), book)) return i;
        }
        return -1;
    }

    private void stopMove(Context ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
    }

    public boolean sold() { return sold; }

    public String describe() {
        return EnchantBooks.describe(book) + " for " + price + " emeralds";
    }

    @Override
    public void stop(Context ctx) {
        if (sub != null) { sub.stop(ctx); sub = null; }
        stopMove(ctx);
        if (talk != null) { talk.stop(ctx); talk = null; }
        ctx.npc().clearDisplayItem();
    }
}
