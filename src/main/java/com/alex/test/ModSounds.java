package com.alex.test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, DynamicVillages.MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_PIANO = SOUNDS.register(
            "bard_piano", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_piano")));
    /** The short violin piece a bard plays for the unhappiest villager nearby. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_VIOLIN = SOUNDS.register(
            "bard_showoff_violin", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_showoff_violin")));
    /** A second violin serenade piece (showoff_violin_2.ogg, 6.875 s, mono), played through twice. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_VIOLIN_2 = SOUNDS.register(
            "bard_showoff_violin_2", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_showoff_violin_2")));
    /** Three more short violin serenade pieces (showoff_violin_1/3/4.ogg, 6.875 s each, mono), each played through twice. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_VIOLIN_1 = simple("bard_showoff_violin_1");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_VIOLIN_3 = simple("bard_showoff_violin_3");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_VIOLIN_4 = simple("bard_showoff_violin_4");
    /** Four bongo showpieces (showoff_bongos_1..4.ogg, 10.29 s each, mono), each played once. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_BONGOS_1 = simple("bard_showoff_bongos_1");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_BONGOS_2 = simple("bard_showoff_bongos_2");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_BONGOS_3 = simple("bard_showoff_bongos_3");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_BONGOS_4 = simple("bard_showoff_bongos_4");
    /**
     * "Sunny Lane", a cheerful solo serenade, on the violin (sunny_lane_violin.ogg, 18.1 s) and
     * on the plucked upright bass (sunny_lane_bass.ogg, 18.0 s). Mono; made by tools/sound/SerenadeSongTool.java.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SUNNY_LANE = simple("bard_sunny_lane");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SUNNY_LANE_BASS = simple("bard_sunny_lane_bass");
    /** "Get Up and Go", an energetic strummed guitar serenade (get_up_and_go_guitar.ogg, 18.0 s, mono). */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_GET_UP_AND_GO = simple("bard_get_up_and_go");
    /** The bard's other serenade piece, on the upright bass (showoff_bass.ogg, 10.0 s, mono). */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_SHOWOFF_BASS = SOUNDS.register(
            "bard_showoff_bass", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_showoff_bass")));
    /**
     * The tavern band's song, "Long Road Ahead": four parts of one 48.0 s piece
     * (long_road_ahead_*.ogg, mono, streamed), started on the same tick so they play together.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_BAND_VIOLIN = simple("bard_band_violin");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_BAND_GUITAR = simple("bard_band_guitar");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_BAND_BASS = simple("bard_band_bass");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARD_BAND_BONGOS = simple("bard_band_bongos");
    /** The soft loop an enchanter's spell-weaving makes while it writes a book, heard by anyone near. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ENCHANTER_ENCHANTING = SOUNDS.register(
            "enchanter_enchanting", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "enchanter_enchanting")));
    /** The ominous loop a player hears up close while an enchanter is in its trance (stereo, not placed). */
    public static final DeferredHolder<SoundEvent, SoundEvent> ENCHANTER_TRANCE = SOUNDS.register(
            "enchanter_trance", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "enchanter_trance")));
    /** The soft loop a druid makes while it grows a sapling into a tree (druid_casting_tree.ogg, 9.69 s, mono, heard 12 blocks). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_TREE = simple("druid_casting_tree");
    /** Played just before a druid's tree appears (druid_casting_tree_finish.ogg, 1.44 s, mono, loudest at 0.85 s). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_TREE_FINISH = simple("druid_casting_tree_finish");
    /** The loop a druid makes while it tends crops (druid_casting_crops.ogg, 9.69 s, mono, heard 12 blocks). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_CROPS = simple("druid_casting_crops");
    /** Played just before a druid's crop becomes fully grown (druid_casting_crops_finish.ogg, 1.44 s, mono, loudest at 0.6 s). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_CROPS_FINISH = simple("druid_casting_crops_finish");
    /** The loop a druid makes while it regrows a mine (druid_casting_shaft.ogg, 9.69 s, mono, mixed loud, heard 12 blocks). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_SHAFT = simple("druid_casting_shaft");
    /** Played just before each mine block grows back (druid_casting_shaft_finish.ogg, 1.69 s, mono, 0.24 s silent start, mixed loud). */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUID_CASTING_SHAFT_FINISH = simple("druid_casting_shaft_finish");

    // The Illager Warlord. Footsteps, swing, slam, door pounding and horn are made by
    // tools/sound/generate_warlord_sounds.py; its voice (roar, hurt, idle, death) by
    // tools/sound/generate_warlord_voice.py.
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_HORN = warlord("horn");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_ROAR = warlord("roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_STEP = warlord("step");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_SWING = warlord("swing");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_SLAM = warlord("slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_HURT = warlord("hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_IDLE = warlord("idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_POUND = warlord("pound");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WARLORD_DEATH = warlord("death");

    private static DeferredHolder<SoundEvent, SoundEvent> warlord(String name) {
        return simple("raid_warlord_" + name);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> simple(String id) {
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, id)));
    }

    private ModSounds() {}

    public static void register(IEventBus bus) { SOUNDS.register(bus); }
}
