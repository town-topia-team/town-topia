package com.alex.test.performance;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the server's tick goes, written to the game log as {@code [ServerCost]} lines
 * every few seconds while on: the whole tick, entity ticks by kind (our villagers, each
 * vanilla mob) with Minecraft's own path searches and pushing inside them, the village
 * ticks with their brains, brain activities and guard plans, and the rest (chunks,
 * block ticks, sending to players, other mods). Bytes sent to players are not measured.
 *
 * <p>Switched with {@code /villagerfps log on|off} in single player (together with the
 * {@code [ClientCost]} lines) or {@code /villager costlog on|off} on any server.
 * Nothing is measured while off: the hooks are one flag check, and the per-entity
 * events are listened to only while on. {@link #start} and {@link #stop} may come from
 * the client thread; they only set the wish, which the server thread acts on next tick.
 * Everything else runs on the server thread.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID)
public final class ServerCostLog {

    private static volatile boolean wanted;
    private static volatile int seconds = 5;

    /** Measuring right now; read by the hooks (server thread). */
    public static boolean measuring;

    private static long windowStart, tickStart;
    private static int ticks, slowTicks;
    private static long tickSum, tickMax;

    /** Entity type -> {ticks, nanos}. */
    private static final Map<EntityType<?>, long[]> ENTITY = new HashMap<>();
    private static long entityStart;
    private static long entitySum, npcNanos;
    private static int npcTicks;

    private static long pathStart, pathNanos, pushStart, pushNanos;
    private static int paths, pushes;

    private static long villageNanos, brainNanos;
    /** Brain activity class -> {runs, nanos}. */
    private static final Map<Class<?>, long[]> ACTIVITY = new HashMap<>();

    private static long gcCountStart, gcTimeStart;

    private static final PerEntity PER_ENTITY = new PerEntity();

    private ServerCostLog() {}

    // -------------------------------------------------------------------------
    // Switch
    // -------------------------------------------------------------------------

    /** Asks for logging, a block of lines every {@code everySeconds}; begins next server tick. */
    public static void start(int everySeconds) {
        seconds = Math.max(1, everySeconds);
        wanted = true;
    }

    public static void stop() {
        wanted = false;
    }

    public static boolean on() {
        return wanted;
    }

    /** {@code /villager costlog [on [seconds]|off]}: the same lines on a dedicated server. */
    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("costlog")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("on")
                        .executes(ctx -> {
                            start(5);
                            return report(ctx.getSource());
                        })
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(ctx -> {
                            start(IntegerArgumentType.getInteger(ctx, "seconds"));
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("off").executes(ctx -> {
                    stop();
                    return report(ctx.getSource());
                }));
    }

    private static int report(CommandSourceStack source) {
        String text = wanted
                ? "Server cost log on: [ServerCost] lines in logs/latest.log every " + seconds + " s"
                : "Server cost log off. /villager costlog on [seconds] writes [ServerCost] lines to logs/latest.log";
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Hooks
    // -------------------------------------------------------------------------

    /** One village's whole tick ({@code VillageManagerRegistry}). */
    public static void village(long nanos) {
        villageNanos += nanos;
    }

    /** One brain tick and the activity it ran ({@code VillageManager}). */
    public static void brain(Object activity, long nanos) {
        brainNanos += nanos;
        long[] slot = ACTIVITY.computeIfAbsent(activity == null ? Void.class : activity.getClass(),
                ignored -> new long[2]);
        slot[0]++;
        slot[1] += nanos;
    }

    /** Minecraft's path search for a mob starts / ends ({@code mixin/ServerCostMixin}). */
    public static void pathStart() {
        pathStart = System.nanoTime();
    }

    public static void pathEnd() {
        if (pathStart == 0L) return;
        pathNanos += System.nanoTime() - pathStart;
        paths++;
        pathStart = 0L;
    }

    /** A living entity pushes those it touches ({@code mixin/ServerCostMixin}). */
    public static void pushStart(LivingEntity entity) {
        if (!entity.level().isClientSide()) pushStart = System.nanoTime();
    }

    public static void pushEnd(LivingEntity entity) {
        if (pushStart == 0L || entity.level().isClientSide()) return;
        pushNanos += System.nanoTime() - pushStart;
        pushes++;
        pushStart = 0L;
    }

    @SubscribeEvent
    public static void tickStart(ServerTickEvent.Pre event) {
        if (wanted != measuring) {
            if (wanted) begin();
            else end();
        }
        if (measuring) tickStart = System.nanoTime();
    }

    @SubscribeEvent
    public static void tickEnd(ServerTickEvent.Post event) {
        if (!measuring || tickStart == 0L) return;
        long now = System.nanoTime();
        long spent = now - tickStart;
        tickSum += spent;
        tickMax = Math.max(tickMax, spent);
        if (spent > 50_000_000L) slowTicks++;
        ticks++;
        if (now - windowStart >= seconds * 1_000_000_000L) {
            write(now);
            reset(now);
        }
    }

    private static void begin() {
        NeoForge.EVENT_BUS.register(PER_ENTITY);
        measuring = true;
        GuardAlertSystem.takePlanCost();
        reset(System.nanoTime());
        DynamicVillages.LOGGER.info("[ServerCost] Logging on, every {} s. Lines: tick, entity ticks by kind, "
                + "village ticks, memory.", seconds);
    }

    private static void end() {
        NeoForge.EVENT_BUS.unregister(PER_ENTITY);
        measuring = false;
        DynamicVillages.LOGGER.info("[ServerCost] Logging off.");
    }

    /** Entity ticks, timed only while logging (they fire once per entity per tick). */
    public static final class PerEntity {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void entityTickStart(EntityTickEvent.Pre event) {
            if (!event.getEntity().level().isClientSide()) entityStart = System.nanoTime();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void entityTickEnd(EntityTickEvent.Post event) {
            Entity entity = event.getEntity();
            if (entityStart == 0L || entity.level().isClientSide()) return;
            long spent = System.nanoTime() - entityStart;
            entityStart = 0L;
            entitySum += spent;
            if (entity instanceof NpcEntity) {
                npcNanos += spent;
                npcTicks++;
                return;
            }
            long[] slot = ENTITY.computeIfAbsent(entity.getType(), type -> new long[2]);
            slot[0]++;
            slot[1] += spent;
        }
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    private static void reset(long now) {
        windowStart = now;
        tickStart = 0L;
        ticks = slowTicks = 0;
        tickSum = tickMax = 0L;
        ENTITY.clear();
        entitySum = npcNanos = 0L;
        npcTicks = 0;
        pathNanos = pushNanos = 0L;
        paths = pushes = 0;
        villageNanos = brainNanos = 0L;
        ACTIVITY.clear();
        long[] gc = gc();
        gcCountStart = gc[0];
        gcTimeStart = gc[1];
    }

    private static void write(long now) {
        if (ticks == 0) return;
        double secs = (now - windowStart) / 1e9;
        double tickMs = ms(tickSum) / ticks;
        double entityMs = ms(entitySum) / ticks;
        double villageMs = ms(villageNanos) / ticks;
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%.1f s: %d ticks (%.1f/s), tick %.1f ms (worst %.1f ms, %d over 50 ms)."
                        + " Per tick: entity ticks %.1f ms, village ticks %.1f ms, rest %.1f ms"
                        + " (chunks, block ticks, sending to players, other mods)",
                secs, ticks, ticks / secs, tickMs, ms(tickMax), slowTicks,
                entityMs, villageMs, Math.max(0, tickMs - entityMs - villageMs)));

        List<Map.Entry<EntityType<?>, long[]>> kinds = new ArrayList<>(ENTITY.entrySet());
        kinds.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder entities = new StringBuilder(String.format(Locale.ROOT,
                "Entity ticks per tick: our villagers %.0f, %.1f ms (%.0f us each);",
                (double) npcTicks / ticks, ms(npcNanos) / ticks, npcTicks == 0 ? 0 : us(npcNanos) / npcTicks));
        long otherNanos = 0, otherCount = 0;
        for (int i = 0; i < kinds.size(); i++) {
            long[] slot = kinds.get(i).getValue();
            if (i < 5) {
                entities.append(String.format(Locale.ROOT, " %s %.0f, %.1f ms (%.0f us each);",
                        EntityType.getKey(kinds.get(i).getKey()).getPath(),
                        (double) slot[0] / ticks, ms(slot[1]) / ticks, us(slot[1]) / slot[0]));
            } else {
                otherCount += slot[0];
                otherNanos += slot[1];
            }
        }
        if (otherCount > 0) {
            entities.append(String.format(Locale.ROOT, " other kinds %.0f, %.1f ms;",
                    (double) otherCount / ticks, ms(otherNanos) / ticks));
        }
        entities.append(String.format(Locale.ROOT, " part of the above: Minecraft path searches %.1f, %.1f ms; pushing %.0f, %.1f ms",
                (double) paths / ticks, ms(pathNanos) / ticks, (double) pushes / ticks, ms(pushNanos) / ticks));
        lines.add(entities.toString());

        List<Map.Entry<Class<?>, long[]>> activities = new ArrayList<>(ACTIVITY.entrySet());
        activities.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        long[] plans = GuardAlertSystem.takePlanCost();
        StringBuilder villages = new StringBuilder(String.format(Locale.ROOT,
                "Village ticks %.1f ms/tick: brains %.1f ms; guard plans %.1f/tick, %.2f ms each; activities (per tick):",
                villageMs, ms(brainNanos) / ticks, (double) plans[0] / ticks, plans[0] == 0 ? 0 : ms(plans[1]) / plans[0]));
        for (int i = 0; i < Math.min(6, activities.size()); i++) {
            Class<?> type = activities.get(i).getKey();
            long[] slot = activities.get(i).getValue();
            villages.append(String.format(Locale.ROOT, " %s %.0f, %.1f ms;",
                    type == Void.class ? "none" : type.getSimpleName(), (double) slot[0] / ticks, ms(slot[1]) / ticks));
        }
        lines.add(villages.toString());

        long[] gc = gc();
        Runtime runtime = Runtime.getRuntime();
        lines.add(String.format(Locale.ROOT, "Memory (whole game): %d collections, %d ms; heap %d of %d MB",
                gc[0] - gcCountStart, gc[1] - gcTimeStart,
                (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20));

        for (String line : lines) DynamicVillages.LOGGER.info("[ServerCost] {}", line);
    }

    /** Collections so far and milliseconds spent in them, all collectors. */
    private static long[] gc() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            time += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, time};
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static double us(long nanos) {
        return nanos / 1e3;
    }
}
