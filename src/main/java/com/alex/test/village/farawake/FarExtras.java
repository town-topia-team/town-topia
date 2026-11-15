package com.alex.test.village.farawake;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * What far villagers set moving, moved too (round 7): arrows, bolts and healing orbs they shoot,
 * animals they lead, and creatures they kill (so those finish dying). Past the simulation
 * distance Minecraft runs none of these: an arrow hung in the air, a led animal stood while its
 * villager walked on, a killed zombie lay there half dead. Once a level tick from
 * {@link FarAwake#tickLevel}, before the village ticks (Minecraft's creature loop is over then).
 *
 * <p>The body rules hold here too. Once a tick: only Minecraft's tick and ours raise
 * {@code Entity.tickCount}, so a count that changed since our last look means Minecraft ran it.
 * Never on ground Minecraft runs. Ready ground under the box, its motion this tick and the edge
 * margin, else it waits and that ground is asked for. Its own catch: a thrower is let go.
 * Server thread only; nothing saved.
 */
public final class FarExtras {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A shot we moved this often and still flying is let go (it waits for a player, as before). */
    static final int SHOT_MOVES = 100;
    /** Dying takes 20 ticks; a creature is let go after this many moves anyway. */
    static final int KILLED_MOVES = 60;
    /** A shot that ran this many ticks without moving has landed. */
    static final int STILL_TICKS = 5;
    static final double STILL_SQ = 1.0E-4;
    /** Nothing is followed longer than this, waits for ground included (30 s). */
    static final int TRACK_LIMIT = 600;
    /** A led animal whose tick threw is not moved by us for this long. */
    static final int LED_COOLDOWN = 200;
    /** Stamps of animals no longer led are cleared this often. */
    private static final int LED_TIDY_EVERY = 200;
    /** One error line per level at most this often (5 minutes). */
    private static final int ERROR_LOG_QUIET = 6000;

    private enum Kind { SHOT, KILLED }

    private enum Step { MOVED, WAITED, FAILED }

    private static final class Tracked {
        final Entity entity;
        final Kind kind;
        final long since;
        int seenTickCount;
        int moves;
        int stillTicks;
        double lastX, lastY, lastZ;

        Tracked(Entity entity, Kind kind, long since) {
            this.entity = entity;
            this.kind = kind;
            this.since = since;
            this.seenTickCount = entity.tickCount;
            this.lastX = entity.getX();
            this.lastY = entity.getY();
            this.lastZ = entity.getZ();
        }
    }

    /** A led animal's tick count after our last look. */
    private static final class Seen {
        int tickCount;
        long lookedAt;
        long coolUntil = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, List<Tracked>> TRACKED = new WeakHashMap<>();
    /** Added since the last pass: a kill during the pass lands here, not in the list being walked. */
    private static final Map<ServerLevel, List<Tracked>> INCOMING = new WeakHashMap<>();
    private static final Map<UUID, Seen> LED_SEEN = new HashMap<>();
    private static final Map<ServerLevel, Long> ERROR_QUIET_UNTIL = new WeakHashMap<>();

    private FarExtras() {}

    /** An arrow, bolt or orb a villager just let fly. Cheap: one look a tick until it lands. */
    public static void trackShot(Entity shot, NpcEntity shooter) {
        if (shot == null || shooter == null) return;
        if (!FarAwake.awake || !FarAwake.extras || shot.isRemoved() || !shooter.isVillageMember()) return;
        if (!(shot.level() instanceof ServerLevel sl)) return;
        // The spawn was refused (another mod can cancel it): ticking it would fly a ghost that still hits.
        if (sl.getEntity(shot.getUUID()) != shot) return;
        incoming(sl).add(new Tracked(shot, Kind.SHOT, sl.getGameTime()));
    }

    /** A creature a villager just killed, on ground Minecraft does not run. */
    static void trackKilled(LivingEntity victim, NpcEntity killer) {
        if (!FarAwake.awake || !FarAwake.extras || !killer.isVillageMember()) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        // Village members are moved with the other far bodies until they have finished dying.
        if (victim instanceof NpcEntity npc && npc.isVillageMember()) return;
        if (sl.isPositionEntityTicking(victim.blockPosition())) return;
        incoming(sl).add(new Tracked(victim, Kind.KILLED, sl.getGameTime()));
    }

    static void tick(ServerLevel sl, FarAwake.LevelState ls, long now) {
        if (!FarAwake.extras) {
            forgetLevel(sl);
            return;
        }
        List<Tracked> in = INCOMING.get(sl);
        List<Tracked> list = TRACKED.get(sl);
        if (in != null && !in.isEmpty()) {
            if (list == null) {
                list = new ArrayList<>();
                TRACKED.put(sl, list);
            }
            list.addAll(in);
            in.clear();
        }
        List<Tracked> riders = null;
        if (list != null && !list.isEmpty()) {
            Iterator<Tracked> it = list.iterator();
            while (it.hasNext()) {
                Tracked t = it.next();
                if (t.kind == Kind.KILLED && t.entity.isPassenger()) {
                    if (riders == null) riders = new ArrayList<>();
                    riders.add(t);
                    continue;
                }
                if (!follow(sl, ls, t, now)) it.remove();
            }
        }
        LedAnimals.forEachLed(sl, (animal, holder) -> followLed(sl, ls, animal, holder, now));
        // Killed riders last: inside view distance a rider is in the level's tick list, so a mount's
        // tick (ours, above) runs it too; its tick count then shows it ran and it is not moved again.
        if (riders != null) {
            for (Tracked t : riders) {
                if (!follow(sl, ls, t, now)) list.remove(t);
            }
        }
        if (now % LED_TIDY_EVERY == 0 && !LED_SEEN.isEmpty()) {
            LED_SEEN.values().removeIf(s -> now - s.lookedAt > LED_TIDY_EVERY);
        }
    }

    /** One look at a shot or a dying creature; false once it is done with. */
    private static boolean follow(ServerLevel sl, FarAwake.LevelState ls, Tracked t, long now) {
        Entity e = t.entity;
        if (e.isRemoved() || e.level() != sl || now - t.since > TRACK_LIMIT) return false;
        // A totem or a heal brought it back: an ordinary far creature again.
        if (t.kind == Kind.KILLED && !(e instanceof LivingEntity living && living.isDeadOrDying())) return false;
        boolean ran = e.tickCount != t.seenTickCount;
        // A killed rider (a chicken jockey) is ticked as a rider: it must finish dying too.
        if (!ran && !sl.isPositionEntityTicking(e.blockPosition()) && (!e.isPassenger() || t.kind == Kind.KILLED)) {
            Step step = move(sl, ls, e, now);
            if (step == Step.FAILED) return false;
            if (step == Step.MOVED) {
                t.moves++;
                ran = true;
            }
        }
        t.seenTickCount = e.tickCount;
        if (e.isRemoved()) return false;
        // Stillness only counts ticks it ran: a shot waiting for its ground has not landed.
        if (ran) {
            double dx = e.getX() - t.lastX, dy = e.getY() - t.lastY, dz = e.getZ() - t.lastZ;
            if (dx * dx + dy * dy + dz * dz < STILL_SQ) {
                if (t.kind == Kind.SHOT && ++t.stillTicks >= STILL_TICKS) return false;
            } else {
                t.stillTicks = 0;
            }
            t.lastX = e.getX();
            t.lastY = e.getY();
            t.lastZ = e.getZ();
        }
        return t.moves < (t.kind == Kind.SHOT ? SHOT_MOVES : KILLED_MOVES);
    }

    /**
     * One look at an animal on a villager's lead. Moved only while its villager moves (Minecraft's
     * tick or ours, this tick or the last): otherwise the lead's own safety net decides.
     */
    private static void followLed(ServerLevel sl, FarAwake.LevelState ls, Animal animal, NpcEntity holder, long now) {
        if (animal.isRemoved() || !animal.isAlive()) return;
        UUID id = animal.getUUID();
        Seen seen = LED_SEEN.get(id);
        if (seen == null) {
            // First look: whether Minecraft ran it this tick is not known, so it is only noted.
            seen = new Seen();
            seen.tickCount = animal.tickCount;
            seen.lookedAt = now;
            LED_SEEN.put(id, seen);
            return;
        }
        boolean ran = animal.tickCount != seen.tickCount;
        seen.lookedAt = now;
        if (!ran && now >= seen.coolUntil && holder.lastBodyTickTime() >= now - 1
                && !sl.isPositionEntityTicking(animal.blockPosition()) && !animal.isPassenger()) {
            if (move(sl, ls, animal, now) == Step.FAILED) seen.coolUntil = now + LED_COOLDOWN;
        }
        seen.tickCount = animal.tickCount;
    }

    /** Asks for the ground under {@code e} and runs its tick when all of it is ready. */
    private static Step move(ServerLevel sl, FarAwake.LevelState ls, Entity e, long now) {
        // Where it is and where its motion takes it this tick (an arrow flies 3 blocks), plus the margin.
        AABB area = e.getBoundingBox().expandTowards(e.getDeltaMovement()).inflate(FarAwake.EDGE_MARGIN);
        int cx0 = SectionPos.blockToSectionCoord(area.minX);
        int cx1 = SectionPos.blockToSectionCoord(area.maxX);
        int cz0 = SectionPos.blockToSectionCoord(area.minZ);
        int cz1 = SectionPos.blockToSectionCoord(area.maxZ);
        boolean ready = true;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long chunk = ChunkPos.asLong(cx, cz);
                ls.book.need(chunk, FarAwake.SYSTEM_ID, FarRank.WORK, now);
                if (ready && !FarHolds.ready(sl, chunk)) ready = false;
            }
        }
        if (!ready) {
            ls.extraWaits++;
            return Step.WAITED;
        }
        try {
            Entity vehicle = e.getVehicle();
            if (vehicle == null) {
                sl.tickNonPassenger(e);
            } else if (vehicle.isRemoved() || !vehicle.hasPassenger(e)) {
                e.stopRiding();
                sl.tickNonPassenger(e);
            } else {
                // ServerLevel.tickPassenger, as in FarBodyTicker: only killed riders get here.
                e.setOldPosAndRot();
                e.tickCount++;
                e.rideTick();
            }
            ls.extraMoves++;
            ls.extraSynced += FarSync.send(sl, e, vehicle);
            return Step.MOVED;
        } catch (Exception | StackOverflowError | LinkageError ex) {
            ls.extraErrors++;
            Long quiet = ERROR_QUIET_UNTIL.get(sl);
            if (quiet == null || now >= quiet) {
                ERROR_QUIET_UNTIL.put(sl, now + ERROR_LOG_QUIET);
                LOGGER.error("[FarAwake] {} threw while being moved far away at {}; the mod stops moving it.",
                        e.getType().getDescription().getString(), e.blockPosition().toShortString(), ex);
            }
            return Step.FAILED;
        }
    }

    private static List<Tracked> incoming(ServerLevel sl) {
        List<Tracked> in = INCOMING.get(sl);
        if (in == null) {
            in = new ArrayList<>();
            INCOMING.put(sl, in);
        }
        return in;
    }

    /** The system or {@code extras} off, nobody online, or the level unloads: nothing followed here. */
    static void forgetLevel(ServerLevel sl) {
        TRACKED.remove(sl);
        INCOMING.remove(sl);
    }

    /** Server start and stop. */
    static void clear() {
        TRACKED.clear();
        INCOMING.clear();
        LED_SEEN.clear();
        ERROR_QUIET_UNTIL.clear();
    }
}
