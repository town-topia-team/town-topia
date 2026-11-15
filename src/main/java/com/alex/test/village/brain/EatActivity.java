package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.TavernBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.stats.DietMemory;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.behavior.TradesmanActivity;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eat queued food portions from the villager's inventory, sitting down first
 * if a free chair is within {@value #CHAIR_RADIUS} blocks, standing where it
 * is otherwise.
 *
 * <p>Each reserved portion owns one complete animation. Hunger is restored only
 * after that animation finishes. Remaining reservations are returned if the
 * activity is interrupted or an animation cannot be started. Nothing is
 * reserved until the villager is in place, so a walk to a chair that gets
 * interrupted has nothing to refund.
 */
public final class EatActivity extends Activity {

    /** The eat clip lasts 7.05 seconds: one serving, from the pocket to the last bite. */
    private static final int EAT_TICKS = 141;
    /** How far a villager will walk to sit down for a meal. */
    private static final int CHAIR_RADIUS = 8;
    /** A walk to a chair taking longer than this gives up and eats standing. */
    private static final int CHAIR_WALK_TIMEOUT_TICKS = 200;
    /** A villager that finished walking further than this from its chair did not really reach it. */
    private static final double SIT_REACH_SQ = 4.0;

    private final Deque<ItemStack> queuedMeals = new ArrayDeque<>();
    private ItemStack activeMeal = ItemStack.EMPTY;
    private int eatingTicksRemaining;
    private boolean completed;
    /** The chair being walked to, claimed; null once seated or eating standing. */
    @Nullable
    private VillageChairs.Chair chair;
    private int chairWalkTicks;
    private boolean eatingStarted;
    /** Happiness this meal has paid out so far, held to {@link FoodCatalog}'s per-meal range. */
    private float mealJoy;
    private boolean seatedJoyGiven;
    private boolean mealHadSpoiled;
    private boolean startedStarving;
    private boolean starvingJoyGiven;
    /** The food the villager most wanted: the one its diet memory records for this meal. */
    @Nullable
    private Item mainDish;
    private boolean mainDishCounted;
    private final Set<Item> servedThisMeal = new HashSet<>();
    /** The evening meal: a proper meal, a dessert if one is carried, and the place counts. */
    private final boolean dinner;
    /** Breakfast, lunch or dinner, rather than a snack; set when the meal is served. */
    private boolean properMeal;
    private boolean placeJoyGiven;
    /** The serving reserved as dessert, eaten for taste rather than hunger. */
    @Nullable
    private Item dessert;

    public EatActivity() {
        this(false);
    }

    public EatActivity(boolean dinner) {
        this.dinner = dinner;
    }

    @Override
    protected void start(Context ctx) {
        ctx.model().stopMoving();
        // Already sitting (say, hungry while waiting in the tavern): eat right there.
        if (!ctx.npc().isChairSeated()) chair = pickChair(ctx);
        if (chair != null) {
            VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());
        } else {
            beginEating(ctx);
        }
    }

    private void beginEating(Context ctx) {
        eatingStarted = true;
        ctx.model().stopMoving();
        reserveMeals(ctx);
        startNextMeal(ctx);
        ctx.npc().holdStillWhileEating();
    }

    /** Walk to the chair and sit; any trouble on the way ends in eating standing up. */
    private void tickWalkToChair(Context ctx) {
        VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());
        boolean arrived = moveTo(ctx, chair.approach(), VillagerModel.WALK_SPEED);
        if (!arrived && ++chairWalkTicks < CHAIR_WALK_TIMEOUT_TICKS) return;

        boolean seated = arrived
                && !moveFailed(ctx, chair.approach())
                && ctx.npc().distanceToSqr(chair.pos().getCenter()) <= SIT_REACH_SQ
                && VillageChairs.isFreeFor(ctx.level(), chair.pos(), ctx.npc().getUUID())
                && ctx.npc().sitOnChair(chair.pos());
        VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
        chair = null;
        if (!seated) ctx.model().stopMoving();
        beginEating(ctx);
    }

    /** The nearest free chair within {@link #CHAIR_RADIUS}, or null. */
    @Nullable
    private static VillageChairs.Chair pickChair(Context ctx) {
        NavGraph graph = ctx.mgr() != null ? ctx.mgr().getGraph() : null;
        if (graph == null || findFoodSlot(ctx) < 0) return null;
        VillageChairs.Chair best = null;
        double bestDist = Double.MAX_VALUE;
        for (VillageChairs.Chair c : VillageChairs.findNear(
                ctx.level(), graph, ctx.npc().blockPosition(), CHAIR_RADIUS)) {
            if (!VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            double d = ctx.npc().distanceToSqr(c.approach().getBottomCenter());
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    /** Stays seated for the whole meal, whether it sat down here or was already sitting. */
    @Override
    public boolean keepsChairSeat() { return true; }

    @Override
    protected Status tick(Context ctx) {
        if (!eatingStarted) {
            tickWalkToChair(ctx);
            return Status.RUNNING;
        }
        if (activeMeal.isEmpty()) return Status.FAILED;

        // If another animation took ownership, this serving was not completed.
        // stop() refunds it together with every queued serving.
        if (!ctx.npc().isEating()) return Status.FAILED;
        ctx.npc().holdStillWhileEating();
        if (--eatingTicksRemaining > 0) {
            if (NpcEntity.isEatBiteTick(EAT_TICKS - eatingTicksRemaining)) {
                ctx.npc().eatBite(activeMeal, 0.6f);
            }
            return Status.RUNNING;
        }

        finishActiveMeal(ctx);
        if (!queuedMeals.isEmpty()) {
            return startNextMeal(ctx) ? Status.RUNNING : Status.FAILED;
        }

        completed = true;
        ctx.npc().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        return Status.DONE;
    }

    @Override
    public void stop(Context ctx) {
        if (chair != null) {
            ctx.model().stopMoving();
            VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
            chair = null;
        }
        if (!completed) refundReservedMeals(ctx);
        ctx.npc().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (ctx.npc().isEating()) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    @Override
    public String name() {
        return "eat";
    }

    public static int findFoodSlot(Context ctx) {
        if (ctx.mgr() == null) return -1;
        // One binding for the whole scan; mayUse(stack, village) builds a new
        // one per call. The food test runs first so non-food slots skip it.
        VillageTokenBinding owner = VillageTokenBinding.of(ctx.mgr());
        InventoryManager inv = ctx.npc().getInventory();
        int[] aside = setAside(ctx);
        for (int i = 0; i < inv.getSlots().length; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.getCount() > aside[i]
                    && VillageItemOwnership.villagerFood(stack, ctx.npc()) != null
                    && VillageItemOwnership.mayUse(stack, owner)) return i;
        }
        return -1;
    }

    /**
     * Units of each pack slot that are not the villager's to eat: a
     * tradesman's sell box goods, which a player put out to be sold. Counted
     * slot by slot in pack order, the same way {@link DepositActivity} keeps
     * them back. A rancher's carrots for the pigs too, while it carries
     * other food (RancherFeedReserve).
     */
    private static int[] setAside(Context ctx) {
        if (ctx.npc().getProfession() == Profession.RANCHER) {
            return com.alex.test.village.villagers.rancher.RancherFeedReserve.heldBackAtMeals(ctx.npc());
        }
        InventoryManager inv = ctx.npc().getInventory();
        int[] aside = new int[inv.getSlots().length];
        if (ctx.npc().getProfession() != Profession.TRADESMAN || ctx.mgr() == null) return aside;
        Map<Item, Integer> left = new HashMap<>(TradesmanActivity.sellBoxHoldings(
                ctx.npc(), ctx.level().getGameTime()));
        if (left.isEmpty()) return aside;
        for (int slot = 0; slot < aside.length; slot++) {
            ItemStack stack = inv.getSlot(slot);
            int forBox = left.getOrDefault(stack.getItem(), 0);
            if (forBox <= 0 || stack.isEmpty() || !VillageItemOwnership.mayTrade(stack, ctx.mgr())) continue;
            aside[slot] = Math.min(forBox, stack.getCount());
            left.put(stack.getItem(), forBox - aside[slot]);
        }
        return aside;
    }

    /**
     * Reserve enough individual servings to bring hunger to the normal
     * post-meal threshold. Removing them now prevents inventory/UI races while
     * a multi-animation meal is in progress.
     *
     * <p>The food the villager most wants comes first and is the meal's main
     * dish: taste, plus the variety bonus or boredom and its likes. A starving
     * villager eats the most filling food first instead. Spoiled food always
     * comes last.
     */
    private void reserveMeals(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        VillagerStats needs = ctx.brain().needs();
        needs.diet.ensureRolled(ctx.npc().getRandom());
        float projectedHunger = needs.hunger;
        // Breakfast, lunch and dinner fill a villager up; a snack between
        // meals only takes the edge off.
        properMeal = dinner || HungerSystem.isMealTime(ctx.level().getDayTime());
        float targetHunger = properMeal
                ? HungerSystem.PROPER_MEAL_TARGET : needs.hungerRules().mealTargetHunger();
        boolean starving = needs.hungerRules().isCritical(needs);
        startedStarving = starving;

        List<Integer> slots = new ArrayList<>();
        Map<Integer, Float> rank = new HashMap<>();
        int[] aside = setAside(ctx);
        for (int slot = 0; slot < inv.getSlots().length; slot++) {
            ItemStack stack = inv.getSlot(slot);
            if (stack.getCount() <= aside[slot]) continue;
            FoodProperties props = VillageItemOwnership.mayUse(stack, ctx.mgr())
                    ? VillageItemOwnership.villagerFood(stack, ctx.npc()) : null;
            if (props == null) continue;
            boolean spoiled = FoodCatalog.tier(stack, props) == FoodCatalog.Tier.SPOILED;
            float score = starving
                    ? servingFill(ctx, stack, props) - (spoiled ? 1000f : 0f)
                    : FoodCatalog.preference(stack, props, needs.diet);
            slots.add(slot);
            rank.put(slot, score);
        }
        slots.sort((a, b) -> Float.compare(rank.get(b), rank.get(a)));

        // Never fill up on food that would mostly go to waste: a serving that
        // overshoots full by more than half its own worth is skipped for
        // something smaller, unless nothing carried fits and the plate is
        // still empty -- a villager must eat something.
        boolean anyFits = false;
        for (int slot : slots) {
            FoodProperties props = VillageItemOwnership.villagerFood(inv.getSlot(slot), ctx.npc());
            if (props != null && !overfills(projectedHunger, servingFill(ctx, inv.getSlot(slot), props))) {
                anyFits = true;
                break;
            }
        }
        for (int slot : slots) {
            if (projectedHunger >= targetHunger) break;
            ItemStack stack = inv.getSlot(slot);
            FoodProperties props = VillageItemOwnership.villagerFood(stack, ctx.npc());
            if (props == null) continue;
            float hunger = servingFill(ctx, stack, props);

            int reservedFromSlot = 0;
            int available = stack.getCount() - aside[slot];
            while (reservedFromSlot < available && projectedHunger < targetHunger) {
                boolean mustEat = queuedMeals.isEmpty() && !anyFits;
                if (overfills(projectedHunger, hunger) && !mustEat) break;
                if (mainDish == null) mainDish = stack.getItem();
                queuedMeals.addLast(stack.copyWithCount(1));
                projectedHunger += hunger;
                reservedFromSlot++;
            }
            if (reservedFromSlot > 0) inv.reduceSlot(slot, reservedFromSlot);
        }
        if (dinner && !starving) reserveDessert(ctx, slots, aside);
    }

    /** Eating this would waste more than half of it past a full stomach. */
    private static boolean overfills(float projectedHunger, float servingHunger) {
        return projectedHunger + servingHunger - 100f > servingHunger * 0.5f;
    }

    /**
     * After dinner, one treat if the villager carries one that is not already
     * on the plate: a cookie, pie, cake or golden food, eaten for its taste
     * even when full. The daily joy cap keeps this from becoming a habit worth
     * more than sleep.
     */
    private void reserveDessert(Context ctx, List<Integer> slotsByPreference, int[] aside) {
        // At the tavern, a slice of tonight's cake comes before anything carried.
        if (ctx.mgr() != null && isInTavern(ctx)) {
            ItemStack slice = FeastTable.takeSlice(ctx.mgr(), ctx.level());
            if (!slice.isEmpty()) {
                dessert = slice.getItem();
                queuedMeals.addLast(slice);
                ctx.brain().diary().log(String.format("%s FEAST cake slice at the tavern",
                        VillagerDiary.ts(ctx.level())));
                return;
            }
        }
        InventoryManager inv = ctx.npc().getInventory();
        for (int slot : slotsByPreference) {
            ItemStack stack = inv.getSlot(slot);
            FoodProperties props = stack.getCount() <= aside[slot] ? null
                    : VillageItemOwnership.villagerFood(stack, ctx.npc());
            if (props == null || !FoodCatalog.tier(stack, props).atLeast(FoodCatalog.Tier.TREAT)) continue;
            if (FoodCatalog.tier(stack, props) == FoodCatalog.Tier.HEARTY) continue;
            if (servedItems().contains(stack.getItem())) continue;
            dessert = stack.getItem();
            queuedMeals.addLast(stack.copyWithCount(1));
            inv.reduceSlot(slot, 1);
            return;
        }
    }

    private Set<Item> servedItems() {
        Set<Item> items = new HashSet<>();
        for (ItemStack queued : queuedMeals) items.add(queued.getItem());
        return items;
    }

    private boolean startNextMeal(Context ctx) {
        activeMeal = queuedMeals.pollFirst();
        if (activeMeal == null || activeMeal.isEmpty()) {
            activeMeal = ItemStack.EMPTY;
            return false;
        }

        ctx.npc().setItemSlot(EquipmentSlot.OFFHAND, activeMeal.copy());
        eatingTicksRemaining = EAT_TICKS;
        if (ctx.npc().startEatAnimation()) return true;

        ctx.npc().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        return false;
    }

    private void finishActiveMeal(Context ctx) {
        FoodProperties props = VillageItemOwnership.villagerFood(activeMeal, ctx.npc());
        if (props != null) {
            float hunger = servingFill(ctx, activeMeal, props);
            boolean chefMade = ctx.mgr() != null
                    && com.alex.test.village.tokens.VillageFood.isChefMade(activeMeal);
            ctx.brain().needs().onFoodEaten(hunger, 0f);
            float joy = applyMealJoy(ctx, activeMeal, props);
            // A proper meal of the cook's that tastes good teaches the cook a
            // little. The dish's own taste, not the chair or the variety.
            if (chefMade && properMeal && FoodCatalog.taste(activeMeal, props)
                    + com.alex.test.village.villagers.cook.CookBonuses.taste(ctx.mgr(),
                            ctx.level().getGameTime(), activeMeal.getItem()) > 0f) {
                praiseCooks(ctx);
            }
            FoodCatalog.Tier tier = FoodCatalog.tier(activeMeal, props);
            // No sound here: each bite of the clip already made its own (eatBite).
            ctx.brain().diary().log(String.format(
                    "%s eat  %s (+%d nutrition, %s%s, joy %+.1f) -> hunger=%.1f wellbeing=%.1f",
                    VillagerDiary.ts(ctx.level()), activeMeal.getItem().getDescriptionId(),
                    props.nutrition(), tier.label,
                    FoodCatalog.isHomeMade(activeMeal) ? "" : ", bought", joy,
                    ctx.brain().needs().hunger, ctx.brain().needs().wellbeing));
            // A good meal that actually filled the villager up is worth
            // remembering; nibbling at 90 hunger, or a plain loaf, is not.
            if (ctx.brain().needs().hunger >= 60f && joy >= 3f) {
                ctx.brain().rememberAndShow(MemoryTopic.GOOD_MEAL,
                        ThoughtIcon.STATUS_SATISFIED, 50, ctx.level());
            }
            returnContainer(ctx);
        }
        activeMeal = ItemStack.EMPTY;
    }

    /**
     * The meal's bowl or bottle back into the pack, as the village's; on its
     * third meal it breaks instead (MealLeftovers). No room: at the feet.
     */
    private void returnContainer(Context ctx) {
        ItemStack empty = MealLeftovers.containerOf(activeMeal);
        if (empty.isEmpty() || ctx.mgr() == null) return;
        ItemStack back = MealLeftovers.leftover(ctx.npc(), activeMeal, ctx.mgr());
        if (back.isEmpty()) {
            ctx.brain().diary().log(String.format("%s eat  the %s broke after %d meals",
                    VillagerDiary.ts(ctx.level()), empty.getItem().getDescriptionId(), MealLeftovers.USES));
            return;
        }
        if (!ctx.npc().getInventory().addItem(back)) {
            ItemLossLog.drop(ctx, back, "no room in the pack for the emptied container");
        }
    }

    /**
     * Hunger one serving really restores, the same when planning a meal and
     * when eating it: a player's food fills less (FoodCatalog.fillShare), and
     * the village cook's Hearty Portions add to the cook's dishes.
     */
    private static float servingFill(Context ctx, ItemStack stack, FoodProperties props) {
        float hunger = HungerSystem.hungerRestoredByFood(props) * FoodCatalog.fillShare(stack, props);
        if (ctx.mgr() != null && com.alex.test.village.tokens.VillageFood.isChefMade(stack)) {
            hunger += com.alex.test.village.villagers.cook.CookBonuses.nutrition(
                    ctx.mgr(), ctx.level().getGameTime()) * HungerSystem.NUTRITION_TO_HUNGER;
        }
        return hunger;
    }

    /** Every working cook in the village hears a dish of theirs was enjoyed (CookBalance.PRAISE_LESSONS). */
    private static void praiseCooks(Context ctx) {
        for (com.alex.test.village.VillagerModel model : ctx.mgr().getVillagers()) {
            com.alex.test.entity.NpcEntity cook = model.puppet();
            if (cook == null || cook == ctx.npc()
                    || cook.getProfession() != com.alex.test.village.villagers.core.Profession.COOK) continue;
            model.brain().onCookPraised(cook);
        }
    }

    /**
     * One serving's happiness: its taste (bought food's good taste halved, a
     * second serving of the same food halved again), the variety bonus or
     * boredom and the villager's likes on the main dish, a chance of feeling
     * sick on bad food, a point for sitting down and one lost for starting the
     * meal starving. The meal as a whole stays inside FoodCatalog's range, and
     * good food inside the daily allowance. Returns what was applied.
     */
    private float applyMealJoy(Context ctx, ItemStack meal, FoodProperties props) {
        DietMemory diet = ctx.brain().needs().diet;
        Item item = meal.getItem();
        boolean spoiled = FoodCatalog.tier(meal, props) == FoodCatalog.Tier.SPOILED;
        float joy = FoodCatalog.taste(meal, props);
        // Seasoned and the Signature Dish: the village's cook makes its dishes tastier.
        if (ctx.mgr() != null && com.alex.test.village.tokens.VillageFood.isChefMade(meal)) {
            joy += com.alex.test.village.villagers.cook.CookBonuses.taste(
                    ctx.mgr(), ctx.level().getGameTime(), item);
        }
        if (!servedThisMeal.add(item) && joy > 0f) joy *= FoodCatalog.REPEAT_SERVING_FACTOR;

        if (item == mainDish && !mainDishCounted) {
            mainDishCounted = true;
            // Only breakfast, lunch and dinner go into the diet memory and
            // earn variety or boredom; a snack between meals would otherwise
            // make the same carried food dull within a couple of days.
            // A player's food: boredom and dislike count, the new-dish and
            // favourite bonus do not (FoodCatalog.varietyJoy).
            float variety = properMeal ? FoodCatalog.varietyJoy(meal, diet) : 0f;
            float liking = FoodCatalog.likingJoy(meal, diet);
            joy += variety + liking;
            if (!spoiled) joy = Math.max(joy, FoodCatalog.TASTE_FLOOR);
            if (properMeal) diet.record(item);
            if (item == diet.favourite() && FoodCatalog.isHomeMade(meal)) {
                ctx.brain().rememberAndShow(MemoryTopic.FAVOURITE_MEAL,
                        ThoughtIcon.EMOTE_LOVE, 50, ctx.level());
            } else if (variety <= -2f) {
                ctx.brain().rememberAndShow(MemoryTopic.BORING_FOOD,
                        ThoughtIcon.EMOTE_BOREDOM, 50, ctx.level());
            }
        }
        if (ctx.npc().getRandom().nextFloat() < FoodCatalog.sickChance(meal, props)) {
            joy += FoodCatalog.SICK_JOY;
            ctx.brain().rememberAndShow(MemoryTopic.SPOILED_FOOD,
                    ThoughtIcon.EMOTE_DISGUST, 50, ctx.level());
        }
        if (!seatedJoyGiven && ctx.npc().isChairSeated()) {
            seatedJoyGiven = true;
            joy += FoodCatalog.SEATED_MEAL_JOY;
        }
        if (dinner && !placeJoyGiven) {
            placeJoyGiven = true;
            joy += placeJoy(ctx);
        }
        if (startedStarving && !starvingJoyGiven) {
            starvingJoyGiven = true;
            joy += FoodCatalog.STARVING_MEAL_JOY;
        }
        // Poor food is a nudge, not a punishment: a meal of raw or plain food
        // bottoms out at POOR_MEAL_MIN however many servings it took. Only
        // spoiled food can take a meal down to MEAL_JOY_MIN.
        if (spoiled) mealHadSpoiled = true;
        float floor = mealHadSpoiled ? FoodCatalog.MEAL_JOY_MIN : FoodCatalog.POOR_MEAL_MIN;
        float allowed = Math.clamp(mealJoy + joy, Math.min(floor, mealJoy), FoodCatalog.MEAL_JOY_MAX)
                - mealJoy;
        mealJoy += allowed;
        return ctx.brain().needs().onFoodJoy(allowed, EveningSchedule.dayIndex(ctx.level().getDayTime()));
    }

    private static boolean isInTavern(Context ctx) {
        BlockPos pos = ctx.npc().blockPosition();
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof TavernBuilding && building.isValid() && building.isInRoom(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Dinner at home, or at the tavern with someone else eating too: one point. */
    private static float placeJoy(Context ctx) {
        if (ctx.mgr() == null) return 0f;
        BlockPos pos = ctx.npc().blockPosition();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!building.isValid() || !building.isInRoom(pos)) continue;
            if (building instanceof HouseBuilding) return FoodCatalog.PLACE_MEAL_JOY;
            if (building instanceof TavernBuilding) {
                AABB around = ctx.npc().getBoundingBox().inflate(8.0);
                for (NpcEntity other : ctx.level().getEntitiesOfClass(NpcEntity.class, around)) {
                    if (other != ctx.npc() && other.isEating()) return FoodCatalog.PLACE_MEAL_JOY;
                }
            }
        }
        return 0f;
    }

    private void refundReservedMeals(Context ctx) {
        if (!activeMeal.isEmpty()) {
            refund(ctx, activeMeal);
            activeMeal = ItemStack.EMPTY;
        }
        while (!queuedMeals.isEmpty()) refund(ctx, queuedMeals.removeFirst());
    }

    private void refund(Context ctx, ItemStack meal) {
        // A tavern cake slice goes back on the table, never into a pack.
        if (FeastTable.isSlice(meal)) {
            if (ctx.mgr() != null) FeastTable.returnSlice(ctx.mgr());
            return;
        }
        if (!ctx.npc().getInventory().addItem(meal)) {
            ItemLossLog.drop(ctx, meal, "interrupted meal could not go back in the pack");
        }
    }
}
