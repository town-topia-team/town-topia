package com.alex.test.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirrors the mod's own log output into the chat of players who asked for it
 * with {@code /villager debug console on}.
 *
 * <p>The console only shows INFO and up, but FML's root logger is at
 * {@code all}, so every {@code LOGGER.debug(...)} under {@code com.alex.test}
 * already reaches Log4j; this appender just picks those events up. It is only
 * attached while someone is subscribed, so it costs nothing otherwise.
 *
 * <p>Log calls arrive on any thread (worker pools, chunk loading), so events
 * are queued and handed to players on the server thread, a few per tick. A
 * flood beyond {@link #MAX_QUEUED} is dropped and reported as a count rather
 * than freezing everyone's chat.
 */
public final class ConsoleChatRelay {

    private static final String LOGGER_PREFIX = "com.alex.test";
    private static final String APPENDER_NAME = "DynamicVillagesChatRelay";
    private static final int MAX_PER_TICK = 8;
    private static final int MAX_QUEUED = 400;
    private static final int MAX_MESSAGE_LENGTH = 300;

    /**
     * What one player wants to see: this level and above, optionally containing
     * {@code filter}. {@code hideInfo} drops INFO lines only, so DEBUG can be
     * read alongside warnings without the routine INFO chatter.
     */
    public record Subscription(Level level, String filter, boolean hideInfo) {}

    private record Line(Level level, String logger, String message) {}

    private static final Map<UUID, Subscription> SUBSCRIBERS = new ConcurrentHashMap<>();
    private static final Queue<Line> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static final AtomicInteger DROPPED = new AtomicInteger();

    private static RelayAppender appender;

    private ConsoleChatRelay() {}

    public static synchronized void subscribe(ServerPlayer player, Level level, String filter, boolean hideInfo) {
        SUBSCRIBERS.put(player.getUUID(), new Subscription(level,
                filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT), hideInfo));
        attach();
    }

    /** @return whether the player had been subscribed */
    public static synchronized boolean unsubscribe(ServerPlayer player) {
        boolean removed = SUBSCRIBERS.remove(player.getUUID()) != null;
        if (SUBSCRIBERS.isEmpty()) detach();
        return removed;
    }

    public static Subscription subscription(ServerPlayer player) {
        return SUBSCRIBERS.get(player.getUUID());
    }

    // -------------------------------------------------------------------------
    // Log4j wiring
    // -------------------------------------------------------------------------

    private static void attach() {
        if (appender != null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        appender = new RelayAppender();
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
    }

    private static void detach() {
        if (appender == null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.getRootLogger().removeAppender(APPENDER_NAME);
        config.getAppenders().remove(APPENDER_NAME);
        ctx.updateLoggers();
        appender.stop();
        appender = null;
        QUEUE.clear();
        QUEUED.set(0);
        DROPPED.set(0);
    }

    private static final class RelayAppender extends AbstractAppender {
        RelayAppender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String logger = event.getLoggerName();
            if (logger == null || !logger.startsWith(LOGGER_PREFIX)) return;
            if (QUEUED.get() >= MAX_QUEUED) {
                DROPPED.incrementAndGet();
                return;
            }
            // Read everything now: the event object may be reused once we return.
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            if (thrown != null) message += " | " + thrown;
            QUEUE.add(new Line(event.getLevel(), shortName(logger), message));
            QUEUED.incrementAndGet();
        }
    }

    private static String shortName(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SUBSCRIBERS.isEmpty()) return;
        MinecraftServer server = event.getServer();

        for (int i = 0; i < MAX_PER_TICK; i++) {
            Line line = QUEUE.poll();
            if (line == null) break;
            QUEUED.decrementAndGet();
            deliver(server, line);
        }

        int dropped = DROPPED.getAndSet(0);
        if (dropped > 0) {
            Component notice = Component.literal("[console] " + dropped
                    + " log lines dropped (too many at once). Full output is in logs/debug.log.")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
            forEachSubscriber(server, (player, sub) -> player.sendSystemMessage(notice));
        }
    }

    private static void deliver(MinecraftServer server, Line line) {
        String text = line.message().length() > MAX_MESSAGE_LENGTH
                ? line.message().substring(0, MAX_MESSAGE_LENGTH) + "…"
                : line.message();
        String haystack = (line.logger() + " " + text).toLowerCase(Locale.ROOT);

        MutableComponent component = Component.literal("[" + line.level().name() + "] ")
                .withStyle(colour(line.level()))
                .append(Component.literal(line.logger() + ": ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));

        forEachSubscriber(server, (player, sub) -> {
            if (!line.level().isMoreSpecificThan(sub.level())) return;
            if (sub.hideInfo() && line.level() == Level.INFO) return;
            if (sub.filter() != null && !haystack.contains(sub.filter())) return;
            player.sendSystemMessage(component);
        });
    }

    private static void forEachSubscriber(MinecraftServer server,
                                          java.util.function.BiConsumer<ServerPlayer, Subscription> action) {
        for (Map.Entry<UUID, Subscription> e : SUBSCRIBERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null) action.accept(player, e.getValue());
        }
    }

    private static ChatFormatting colour(Level level) {
        if (level.isMoreSpecificThan(Level.ERROR)) return ChatFormatting.RED;
        if (level.isMoreSpecificThan(Level.WARN)) return ChatFormatting.GOLD;
        if (level.isMoreSpecificThan(Level.INFO)) return ChatFormatting.WHITE;
        return ChatFormatting.DARK_GRAY;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (ConsoleChatRelay.class) {
            SUBSCRIBERS.clear();
            detach();
        }
    }
}
