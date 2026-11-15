package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.lumberjack.LumberjackBigTrees;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.stats.VillagerStats;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * The single highest-priority problem a villager currently has, for the
 * floating thought-icon UI. Server-side truth only: {@link #icon()} is what
 * {@link com.alex.test.village.social.ThoughtBubbles} floats over the
 * villager's head, and {@link #glyph()} the ASCII equivalent still appended to
 * the debug nameplate.
 *
 * <p>{@link #compute} is read-only and re-derives everything from live state
 * every call (needs, profession, buildings), except village food presence,
 * which is the brain's own 40-tick cache so the thought matches its
 * decisions. It is called
 * every {@code StateDump} and on every activity switch, so every step here
 * must stay cheap: field reads, a handful of building-list scans bounded by
 * village size, and one delegated {@code hasImmediateWork} check that is
 * itself required to be cheap by the {@link WorkActivity} contract.
 */
public enum ThoughtState {
    OK("OK", "", null, null, null),
    HUNGRY("HUNGRY", "[hungry]",
            ThoughtIcon.STATUS_HUNGRY, MemoryTopic.HUNGER, ThoughtIcon.STATUS_SATISFIED),
    STARVING("STARVING", "[starving]",
            ThoughtIcon.STATUS_STARVING, MemoryTopic.STARVATION, ThoughtIcon.STATUS_SATISFIED),
    NO_FOOD_IN_VILLAGE("NO_FOOD_IN_VILLAGE", "[no food]",
            ThoughtIcon.STATUS_NO_FOOD, MemoryTopic.FOOD_SHORTAGE, ThoughtIcon.EMOTE_RELIEF),
    // The larder has food, but every chest holding it was out of this
    // villager's reach. Shown so a player sees a path problem, not a
    // hungry villager standing next to a full storage room.
    FOOD_UNREACHABLE("FOOD_UNREACHABLE", "[can't reach food]",
            ThoughtIcon.TOPIC_STORAGE, MemoryTopic.FOOD_SHORTAGE, ThoughtIcon.EMOTE_RELIEF),
    HOMELESS("HOMELESS", "[no bed]",
            ThoughtIcon.STATUS_NO_BED, MemoryTopic.NO_BED, ThoughtIcon.EMOTE_RELIEF),
    UNHAPPY("UNHAPPY", "[unhappy]",
            ThoughtIcon.STATUS_SAD, MemoryTopic.MISERY, ThoughtIcon.STATUS_HAPPY),
    // No memory topic: SLEEPY is true of every villager at the end of every
    // working day, so recording it would weight "I am tired" above everything
    // that actually happened to this one. A bad night is recorded at the day rollover
    // instead, from whether it got a bed -- see VillagerBrain.handleDayRollover.
    SLEEPY("SLEEPY", "[sleepy]",
            ThoughtIcon.STATUS_SLEEPY, null, ThoughtIcon.EMOTE_RELIEF),
    WORKLESS("WORKLESS", "[no work]",
            ThoughtIcon.STATUS_NO_WORK, MemoryTopic.NO_WORK, ThoughtIcon.TOPIC_WORK),
    WORKLESS_LUMBERJACK("WORKLESS", "[no work]",
            ThoughtIcon.STATUS_NO_TREES, MemoryTopic.NO_WORK, ThoughtIcon.TOPIC_WORK),
    // A lumberjack with no work whose only trees left are big 2x2 ones, and
    // an axe below iron (LumberjackBigTrees). Shown instead of "no trees":
    // the trees are there, the axe is the problem.
    NEEDS_BIG_AXE("NEEDS_BIG_AXE", "[needs iron axe]",
            ThoughtIcon.STATUS_NO_TOOLS, MemoryTopic.NO_TOOLS, ThoughtIcon.EMOTE_RELIEF);

    private final String      label;
    private final String      glyph;
    private final ThoughtIcon icon;
    private final MemoryTopic memory;
    private final ThoughtIcon resolvedIcon;

    ThoughtState(String label, String glyph,
                 @Nullable ThoughtIcon icon,
                 @Nullable MemoryTopic memory,
                 @Nullable ThoughtIcon resolvedIcon) {
        this.label        = label;
        this.glyph        = glyph;
        this.icon         = icon;
        this.memory       = memory;
        this.resolvedIcon = resolvedIcon;
    }

    /** Short readable-ASCII marker for the nameplate; empty for {@link #OK}. */
    public String glyph() { return glyph; }

    public String label() { return label; }

    /** Icon floated over the villager's head for this problem; null for {@link #OK}. */
    @Nullable
    public ThoughtIcon icon() { return icon; }

    /** What the villager will remember having been through; null for {@link #OK}. */
    @Nullable
    public MemoryTopic memory() { return memory; }

    /** Icon shown once, on its own, when this problem stops applying. */
    @Nullable
    public ThoughtIcon resolvedIcon() { return resolvedIcon; }

    /**
     * Highest-priority applicable state, in the order:
     * STARVING &gt; NO_FOOD_IN_VILLAGE &gt; FOOD_UNREACHABLE &gt; HUNGRY &gt; HOMELESS &gt; UNHAPPY &gt; SLEEPY &gt; WORKLESS &gt; OK.
     * A lumberjack's WORKLESS is WORKLESS_LUMBERJACK, or NEEDS_BIG_AXE when only big trees are left for its axe.
     *
     * <p>{@code HOMELESS} folds into the "no bed assigned at night" reading of
     * {@link VillageManager#getAssignedBed} — that accessor is public, so the
     * dedicated tier from the design doc is kept rather than dropped to
     * UNHAPPY. {@code NO_FOOD_IN_VILLAGE} reads the brain's cached
     * village-food answer, the same one its fetch and work decisions use.
     */
    public static ThoughtState compute(VillagerBrain brain, NpcEntity npc, ServerLevel level) {
        if (brain == null || npc == null || level == null) return OK;

        VillagerStats needs = brain.needs();
        float hunger = needs.hunger;
        float wellbeing = needs.wellbeing;

        if (hunger <= 5f) return STARVING;

        boolean hungry = hunger < 30f;
        VillageManager mgr = resolveManager(npc, level);

        if (hungry) {
            if (mgr != null && !brain.villageHasFoodCached(mgr, level)) return NO_FOOD_IN_VILLAGE;
            if (brain.isFoodUnreachable(level.getGameTime())) return FOOD_UNREACHABLE;
            return HUNGRY;
        }

        // The bedtime activity itself found nowhere to sleep -- no bed it can
        // reach and no free town-hall spot -- and is roaming the village.
        // Asked directly because the village-wide "no free bed" test below
        // misses a villager whose free beds are all out of reach.
        if (brain.currentActivity() instanceof GoHomeActivity home && home.hasNowhereToSleep()) {
            return HOMELESS;
        }

        boolean night = VillagerBrain.isNight(level);
        // Homeless means the village has nowhere for this villager to sleep --
        // not merely that it has not claimed a bed yet. Beds are claimed lazily
        // at bedtime, so testing only `getAssignedBed` put "[no bed]" over the
        // head of every villager in a village full of empty beds, including
        // ones already asleep in one.
        if (night && mgr != null && !npc.isSleeping()
                && mgr.getAssignedBed(npc.getNpcId()) == null
                && !mgr.hasFreeBed()) {
            return HOMELESS;
        }

        // Tired, not "it is night": the same floor that ends a villager's
        // working day, so a rested villager out after dusk shows nothing and
        // one that wore itself out by mid-afternoon shows it straight away.
        if (wellbeing < 15f) return UNHAPPY;

        if (wellbeing <= WellbeingSystem.SAD_THRESHOLD
                && !npc.isSleeping()) {
            return SLEEPY;
        }

        Profession profession = npc.getProfession();
        // A cleric with nobody to look after is good news, not a complaint.
        // Nor an enchanter with every tool covered or its books for the day written.
        if (profession != Profession.NONE && profession != Profession.CHILD
                && profession != Profession.CLERIC
                && !(profession == Profession.ENCHANTER && com.alex.test.village.villagers.enchanter
                        .behavior.EnchanterActivity.restingContented(npc))
                && !(brain.currentActivity() instanceof WorkActivity)
                && mgr != null) {
            VillagerModel model = mgr.getModel(npc.getNpcId());
            if (model != null) {
                Activity.Context ctx = new Activity.Context(brain, model, mgr, level, npc);
                if (!brain.hasImmediateWorkForThought(ctx)) {
                    if (profession != Profession.LUMBERJACK) return WORKLESS;
                    return LumberjackBigTrees.waitingForAxe(npc, level.getGameTime())
                            ? NEEDS_BIG_AXE : WORKLESS_LUMBERJACK;
                }
            }
        }

        return OK;
    }

    // =========================================================================
    // Village lookups (same resolve pattern as LumberjackSettingsChangeHandler)
    // =========================================================================

    @Nullable
    private static VillageManager resolveManager(NpcEntity npc, ServerLevel level) {
        VillagerVillageLink link = npc.getVillageLink();
        if (link == null) return null;

        VillageManager manager = VillageManagerRegistry.get(level.getServer()).get(link.villageId());
        if (manager != null) return manager;
        return VillageManager.INSTANCE.getVillageId().equals(link.villageId())
                ? VillageManager.INSTANCE
                : null;
    }
}
