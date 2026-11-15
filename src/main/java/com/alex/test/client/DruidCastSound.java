package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcEntity.DruidCast;
import com.alex.test.village.villagers.druid.behavior.DruidSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The soft hum a druid makes while it dances a sapling into a tree, tends a
 * crop or regrows a mine. Started on this client for the nearest druids whose
 * {@link NpcEntity#getDruidCast()} is set; follows the druid, and fades out
 * when that goes off (a moment before a tree or ripe crop, as the finish
 * sound takes over), when the druid stops dancing or is gone, or when the
 * player walks away.
 *
 * <p>The tree and crop files fade in and out, so on plain repeat they would
 * drop to near silence every 9.7 s. Instead each hum plays copies of the
 * file, the next starting a little before the last one ends: the two fades
 * overlap and the seam sounds like the file's own soft dips. The mine file
 * has no fades (overlapping it would double it), so it plays once on
 * Minecraft's own gapless repeat.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class DruidCastSound {
    /** Started within this range: the sound itself is heard 12 blocks (sounds.json). */
    private static final double START_RANGE = 12.0;
    /** Dropped past this, so a player walking off does not keep it running silent. */
    private static final double STOP_RANGE = 16.0;
    /** Druids heard at once, whatever they work on: the nearest two, so a forest full of druids is not a choir. */
    private static final int MAX_HUMS = 2;
    private static final int FADE_IN_TICKS = 20;
    /** As long as the tree finish sound's default lead, so the hum is gone as the tree appears. */
    private static final int FADE_OUT_TICKS = 10;
    private static final int SCAN_INTERVAL = 10;

    /** Which file each job hums, how its copies follow on, and how loud. */
    private enum Kind {
        /** Copies 9.1 s apart: the file's 9.69 s with its 0.6 s fades overlapping. */
        TREE(DruidCast.TREE, ModSounds.DRUID_CASTING_TREE, 182, DruidSounds::loopPercent),
        /** Copies 9.4 s apart: the file fades in over 0.5 s and drops softly over its last 0.3 s. */
        CROPS(DruidCast.CROPS, ModSounds.DRUID_CASTING_CROPS, 188, DruidSounds::cropLoopPercent),
        /** One copy, repeated by the sound engine. */
        MINE(DruidCast.MINE, ModSounds.DRUID_CASTING_SHAFT, 0, DruidSounds::mineLoopPercent);

        final DruidCast cast;
        final Supplier<SoundEvent> sound;
        /** From one copy's start to the next; 0 = one copy on repeat. */
        final int copyTicks;
        final IntSupplier percent;

        Kind(DruidCast cast, Supplier<SoundEvent> sound, int copyTicks, IntSupplier percent) {
            this.cast = cast;
            this.sound = sound;
            this.copyTicks = copyTicks;
            this.percent = percent;
        }

        static Kind of(DruidCast cast) {
            for (Kind kind : values()) {
                if (kind.cast == cast) return kind;
            }
            return null;
        }
    }

    private static final Map<UUID, Hum> HUMS = new HashMap<>();

    private DruidCastSound() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isPaused()) return;
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            stopAll();
            return;
        }
        if (level.getGameTime() % SCAN_INTERVAL == 0) scan(level, player);
        for (Iterator<Hum> it = HUMS.values().iterator(); it.hasNext(); ) {
            Hum hum = it.next();
            if (!hum.tick(minecraft, level, player)) {
                hum.done = true;
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAll();
    }

    private static void stopAll() {
        for (Hum hum : HUMS.values()) hum.done = true;
        HUMS.clear();
    }

    /** Dancing on a job, and that job's finish sound has not started yet. */
    private static boolean isHumming(NpcEntity npc) {
        return npc.getDruidCast() != DruidCast.NONE && npc.getAnimation() == VillagerAnimationState.DANCE;
    }

    /**
     * Picks the nearest humming druids. One already heard keeps its place out
     * to {@link #STOP_RANGE}; a new one has to come within {@link #START_RANGE}.
     * A druid that changes job keeps its place; its old hum fades out and the
     * new one starts at the next scan after.
     */
    private static void scan(ClientLevel level, LocalPlayer player) {
        List<NpcEntity> near = level.getEntitiesOfClass(NpcEntity.class,
                player.getBoundingBox().inflate(STOP_RANGE), npc -> {
                    if (!isHumming(npc)) return false;
                    double range = HUMS.containsKey(npc.getUUID()) ? STOP_RANGE : START_RANGE;
                    return npc.distanceToSqr(player) <= range * range;
                });
        near.sort(Comparator.comparingDouble(npc -> npc.distanceToSqr(player)));
        Set<UUID> chosen = new HashSet<>();
        for (NpcEntity npc : near) {
            if (chosen.size() >= MAX_HUMS) break;
            Kind kind = Kind.of(npc.getDruidCast());
            if (kind == null) continue;
            chosen.add(npc.getUUID());
            HUMS.computeIfAbsent(npc.getUUID(), id -> new Hum(npc, level, kind));
        }
        for (Hum hum : HUMS.values()) hum.chosen = chosen.contains(hum.druidId);
    }

    /** One druid's hum: its fade, where it plays from, and when the next copy starts. */
    private static final class Hum {
        final UUID druidId;
        final int entityId;
        final ClientLevel level;
        final Kind kind;
        /** One of the nearest humming druids at the last scan. */
        boolean chosen = true;
        /** Set once dropped; its copies stop on their next tick. */
        boolean done;
        /** 0..1; climbs while wanted, falls once not. */
        float fade;
        float volume;
        int ticksToNextCopy;
        double x, y, z;

        Hum(NpcEntity druid, ClientLevel level, Kind kind) {
            this.druidId = druid.getUUID();
            this.entityId = druid.getId();
            this.level = level;
            this.kind = kind;
            follow(druid);
        }

        /** One tick; false once it has faded out or the druid is gone. */
        boolean tick(Minecraft minecraft, ClientLevel current, LocalPlayer player) {
            Entity entity = level.getEntity(entityId);
            if (current != level || !(entity instanceof NpcEntity druid) || !druid.isAlive()
                    || !druid.getUUID().equals(druidId)) {
                return false;
            }
            follow(druid);
            boolean wanted = chosen && isHumming(druid) && druid.getDruidCast() == kind.cast
                    && druid.distanceToSqr(player) <= STOP_RANGE * STOP_RANGE;
            // A one-tick blip in the flag only dips the hum; it does not restart it.
            if (wanted) {
                fade = Math.min(1.0f, fade + 1.0f / FADE_IN_TICKS);
            } else {
                fade -= 1.0f / FADE_OUT_TICKS;
                if (fade <= 0.0f) return false;
            }
            volume = kind.percent.getAsInt() / 100.0f * fade;
            if (--ticksToNextCopy <= 0) {
                // One copy on repeat never needs a next one.
                ticksToNextCopy = kind.copyTicks > 0 ? kind.copyTicks : Integer.MAX_VALUE;
                minecraft.getSoundManager().play(new Copy(this));
            }
            return true;
        }

        void follow(NpcEntity druid) {
            x = druid.getX();
            y = druid.getY() + 1.2;
            z = druid.getZ();
        }
    }

    /** One play-through of the file (or the file on repeat), following its hum; ends when the hum is dropped. */
    private static final class Copy extends AbstractTickableSoundInstance {
        private final Hum hum;

        Copy(Hum hum) {
            super(hum.kind.sound.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.hum = hum;
            this.looping = hum.kind.copyTicks <= 0;
            this.delay = 0;
            copyHum();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (hum.done) {
                stop();
                return;
            }
            copyHum();
        }

        private void copyHum() {
            x = hum.x;
            y = hum.y;
            z = hum.z;
            volume = hum.volume;
        }
    }
}
