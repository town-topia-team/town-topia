package com.alex.test.village.social;

import com.alex.test.DynamicVillages;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * One drawable icon a villager can float above its head.
 *
 * <p>The set is the delivered art in {@code assets/dynamicvillages/textures/thought}
 * — 17 needs/status faces, 24 conversation reactions and 24 conversation topics
 * — and the enum order is the on-the-wire order, so entries may be appended but
 * never reordered or removed (see {@link #networkId()}).
 *
 * <p>Lives in the common source set rather than {@code client}: the server picks
 * which icon a villager shows and only the {@link #texture()} accessor is
 * client-relevant. The {@link ResourceLocation} is built on first use, not in
 * the constructor, so the enum still loads without Minecraft on the classpath
 * (the plain JUnit tests).
 *
 * <p>{@link Valence} is what the rest of the system reasons about — it decides
 * the pitch of the "huh" that accompanies a thought and drives the fallback
 * reply tables in {@link ConversationScripts}, so a new icon only has to declare
 * whether it reads as good, bad, or neither to take part in conversations.
 */
public enum ThoughtIcon {

    // -- Needs and status (16) ------------------------------------------------
    STATUS_HUNGRY    ("status_hungry",    Valence.NEGATIVE),
    STATUS_STARVING  ("status_starving",  Valence.NEGATIVE),
    STATUS_NO_FOOD   ("status_no_food",   Valence.NEGATIVE),
    STATUS_NO_BED    ("status_no_bed",    Valence.NEGATIVE),
    STATUS_SLEEPY    ("status_sleepy",    Valence.NEGATIVE),
    STATUS_EXHAUSTED ("status_exhausted", Valence.NEGATIVE),
    STATUS_SAD       ("status_sad",       Valence.NEGATIVE),
    STATUS_LONELY    ("status_lonely",    Valence.NEGATIVE),
    STATUS_HAPPY     ("status_happy",     Valence.POSITIVE),
    STATUS_NO_TOOLS  ("status_no_tools",  Valence.NEGATIVE),
    STATUS_NO_WORK   ("status_no_work",   Valence.NEGATIVE),
    STATUS_FLEEING   ("status_fleeing",   Valence.NEGATIVE),
    STATUS_AFRAID    ("status_afraid",    Valence.NEGATIVE),
    STATUS_INJURED   ("status_injured",   Valence.NEGATIVE),
    STATUS_STUCK     ("status_stuck",     Valence.NEGATIVE),
    STATUS_SATISFIED ("status_satisfied", Valence.POSITIVE),

    // -- Conversation reactions (24) -----------------------------------------
    EMOTE_GREETING    ("emote_greeting",    Valence.POSITIVE),
    EMOTE_TALKING     ("emote_talking",     Valence.NEUTRAL),
    EMOTE_AGREE       ("emote_agree",       Valence.POSITIVE),
    EMOTE_DISAGREE    ("emote_disagree",    Valence.NEGATIVE),
    EMOTE_QUESTION    ("emote_question",    Valence.NEUTRAL),
    EMOTE_IDEA        ("emote_idea",        Valence.POSITIVE),
    EMOTE_SURPRISED   ("emote_surprised",   Valence.NEUTRAL),
    EMOTE_LAUGHTER    ("emote_laughter",    Valence.POSITIVE),
    EMOTE_ANGER       ("emote_anger",       Valence.NEGATIVE),
    EMOTE_APOLOGY     ("emote_apology",     Valence.NEUTRAL),
    EMOTE_GRATITUDE   ("emote_gratitude",   Valence.POSITIVE),
    EMOTE_LOVE        ("emote_love",        Valence.POSITIVE),
    EMOTE_HEARTBREAK  ("emote_heartbreak",  Valence.NEGATIVE),
    EMOTE_GOSSIP      ("emote_gossip",      Valence.NEUTRAL),
    EMOTE_DOUBT       ("emote_doubt",       Valence.NEGATIVE),
    EMOTE_PRIDE       ("emote_pride",       Valence.POSITIVE),
    EMOTE_ADMIRATION  ("emote_admiration",  Valence.POSITIVE),
    EMOTE_BOREDOM     ("emote_boredom",     Valence.NEGATIVE),
    EMOTE_RELIEF      ("emote_relief",      Valence.POSITIVE),
    EMOTE_CONFUSION   ("emote_confusion",   Valence.NEUTRAL),
    EMOTE_WARNING     ("emote_warning",     Valence.NEGATIVE),
    EMOTE_CELEBRATION ("emote_celebration", Valence.POSITIVE),
    EMOTE_ENVY        ("emote_envy",        Valence.NEGATIVE),
    EMOTE_DISGUST     ("emote_disgust",     Valence.NEGATIVE),

    // -- Conversation topics (24) --------------------------------------------
    TOPIC_FOOD       ("topic_food",       Valence.NEUTRAL),
    TOPIC_BED        ("topic_bed",        Valence.NEUTRAL),
    TOPIC_HOME       ("topic_home",       Valence.NEUTRAL),
    TOPIC_TOOLS      ("topic_tools",      Valence.NEUTRAL),
    TOPIC_WORK       ("topic_work",       Valence.NEUTRAL),
    TOPIC_FARMING    ("topic_farming",    Valence.NEUTRAL),
    TOPIC_LIVESTOCK  ("topic_livestock",  Valence.NEUTRAL),
    TOPIC_COOKING    ("topic_cooking",    Valence.NEUTRAL),
    TOPIC_WOOD       ("topic_wood",       Valence.NEUTRAL),
    TOPIC_MINING     ("topic_mining",     Valence.NEUTRAL),
    TOPIC_SMITHING   ("topic_smithing",   Valence.NEUTRAL),
    TOPIC_BUILDING   ("topic_building",   Valence.NEUTRAL),
    TOPIC_TRADING    ("topic_trading",    Valence.NEUTRAL),
    TOPIC_LEARNING   ("topic_learning",   Valence.NEUTRAL),
    TOPIC_MUSIC      ("topic_music",      Valence.NEUTRAL),
    TOPIC_HEALING    ("topic_healing",    Valence.NEUTRAL),
    TOPIC_ENCHANTING ("topic_enchanting", Valence.NEUTRAL),
    TOPIC_GUARDING   ("topic_guarding",   Valence.NEUTRAL),
    TOPIC_ARCHERY    ("topic_archery",    Valence.NEUTRAL),
    TOPIC_STORAGE    ("topic_storage",    Valence.NEUTRAL),
    TOPIC_MAYOR      ("topic_mayor",      Valence.NEUTRAL),
    TOPIC_FAMILY     ("topic_family",     Valence.NEUTRAL),
    TOPIC_NATURE     ("topic_nature",     Valence.NEUTRAL),
    TOPIC_WEATHER    ("topic_weather",    Valence.NEUTRAL),

    // Appended to preserve the network IDs of all previously shipped icons.
    STATUS_OVERCROWDED ("status_overcrowded", Valence.NEGATIVE),
    STATUS_NO_TREES    ("status_no_trees",    Valence.NEGATIVE);

    /** How an icon reads to a bystander; drives sound pitch and reply choice. */
    public enum Valence { NEGATIVE, NEUTRAL, POSITIVE }

    private static final ThoughtIcon[] VALUES = values();
    private static final Map<String, ThoughtIcon> BY_ID = new HashMap<>();

    static {
        for (ThoughtIcon icon : VALUES) BY_ID.put(icon.id, icon);
    }

    private final String           id;
    private final Valence          valence;
    private       ResourceLocation texture;

    ThoughtIcon(String id, Valence valence) {
        this.id      = id;
        this.valence = valence;
    }

    /** Asset id, matching both the art manifest and the PNG file name. */
    public String id() { return id; }

    public Valence valence() { return valence; }

    public boolean isNegative() { return valence == Valence.NEGATIVE; }

    public ResourceLocation texture() {
        if (texture == null) {
            texture = ResourceLocation.fromNamespaceAndPath(
                    DynamicVillages.MODID, "textures/thought/" + id + ".png");
        }
        return texture;
    }

    /**
     * Network form, one-based so that {@code 0} can mean "no bubble" in the
     * packed entity-data field (see {@code NpcEntity#showBubble}).
     */
    public int networkId() { return ordinal() + 1; }

    /** Inverse of {@link #networkId()}; {@code null} for 0 or anything unknown. */
    @Nullable
    public static ThoughtIcon byNetworkId(int networkId) {
        int index = networkId - 1;
        return index >= 0 && index < VALUES.length ? VALUES[index] : null;
    }

    /** Lookup by asset id, for commands and debug tooling. */
    @Nullable
    public static ThoughtIcon byId(String id) { return BY_ID.get(id); }

    /** All asset ids, for command suggestions. */
    public static java.util.stream.Stream<String> ids() {
        return java.util.Arrays.stream(VALUES).map(ThoughtIcon::id);
    }
}
