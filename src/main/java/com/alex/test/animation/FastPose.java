package com.alex.test.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The switch for faster villager posing (plain villager FPS plan V1; plan step 0 of
 * docs/plans/2026-10-22-hundred-villagers-fps.md): {@link FastController} for each animation
 * track, and the client's {@code FastAnimationProcessor} for the posing step around them. Both
 * give the same pose as GeckoLib's own code, without its waste.
 *
 * <p>Both copy parts of GeckoLib 4.8.4's posing, so they are only right while GeckoLib's own
 * copies of those methods are still that code. Instead of a list of version numbers, the game
 * asks the code itself: at the first posing it reads GeckoLib's posing methods out of the jar
 * that is loaded ({@link GeckoFingerprint}) and compares them with the codes stored from 4.8.4.
 * Three speeds, picked by the game, never a crash:
 * <ul>
 *   <li>{@link Mode#FULL}: the same, whatever the version number: our copies run. On a GeckoLib
 *       other than the stored codes' version a same-pose self-check runs by itself for the first
 *       minute villagers are drawn; any difference drops to SAFE until restart.</li>
 *   <li>{@link Mode#SAFE}: something differs, or the code could not be read: GeckoLib's own
 *       posing runs, handed only the bones the loaded clips key and with stopped tracks' queues
 *       emptied (public GeckoLib parts only; same pose by construction, about half the saving).</li>
 *   <li>{@link Mode#GECKO}: GeckoLib untouched: chosen by command, or after a link error
 *       ({@link #broken}).</li>
 * </ul>
 * {@code /villagerfps anim fast|safe|gecko|auto|check|fingerprint}. Client side, not saved.
 */
public final class FastPose {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The three speeds. */
    public enum Mode {
        /** Our copies of GeckoLib's posing ({@link FastController} + {@code FastAnimationProcessor}). */
        FULL,
        /** GeckoLib's own posing, handed only the bones the clips key; stopped tracks' queues emptied. */
        SAFE,
        /** GeckoLib's posing untouched. */
        GECKO
    }

    /** The stored codes, at the root of the mod jar (written by {@link GeckoFingerprint#main}). */
    static final String STORED_FILE = "/fastpose-fingerprint.txt";
    /** How long the self-check runs on a GeckoLib other than the stored codes' version. */
    static final int SELF_CHECK_SECONDS = 60;

    /** Set by command; null = the game picks. */
    private static volatile Mode chosen;
    /** Set once something GeckoLib-side failed to link: GECKO until restart. */
    private static volatile boolean failed;
    private static String failedWhy = "";
    /** The game's pick from the fingerprint (and the self-check); null until the code was read. */
    private static volatile Mode decided;
    private static String geckoVersion = "unknown";
    private static String storedVersion = "unknown";
    private static int membersCompared;
    private static List<String> changed = List.of();
    private static boolean selfCheckWanted, selfCheckRunning, selfCheckPassed, selfCheckFailed;

    private FastPose() {}

    /** How villagers are posed right now. Render thread. */
    public static Mode mode() {
        if (failed) return Mode.GECKO;
        Mode pick = chosen;
        if (pick != null) return pick;
        Mode now = decided;
        if (now == null) now = decide();
        if (now == Mode.FULL && selfCheckWanted && !selfCheckRunning) startSelfCheck();
        return now;
    }

    /** Whether villagers are posed by our copies right now (full fast). */
    public static boolean on() {
        return mode() == Mode.FULL;
    }

    /** {@code /villagerfps anim fast|safe|gecko} ({@code auto}: null, the game picks again). */
    public static void choose(Mode mode) {
        chosen = mode;
    }

    /** The GeckoLib running now, e.g. "4.8.4", or "unknown". */
    public static String geckoVersion() {
        mode();
        return geckoVersion;
    }

    // ---------------------------------------------------------------------------------
    // The decision: is GeckoLib's posing code still the code our copies came from?
    // ---------------------------------------------------------------------------------

    private static synchronized Mode decide() {
        Mode already = decided;
        if (already != null) return already;
        String version;
        try {
            version = ModList.get().getModContainerById("geckolib")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (RuntimeException e) {
            return Mode.SAFE;                // mod list not ready: safe for now, decided next time
        }
        geckoVersion = version;
        try {
            GeckoFingerprint.Stored stored = readStored();
            storedVersion = stored.version();
            membersCompared = stored.codes().size();
            Map<String, String> loaded = GeckoFingerprint.all(FastPose::classBytes);
            changed = GeckoFingerprint.differences(stored.codes(), loaded);
        } catch (Throwable e) {              // no class reader, no stored file, a reader error: never a crash
            changed = List.of("the posing code could not be read: " + e);
            LOGGER.warn("[FastPose] could not compare GeckoLib {}'s posing code with the stored {} codes; "
                    + "safe posing (GeckoLib's own code, handed only the bones the clips move)", version, storedVersion, e);
            decided = Mode.SAFE;
            return Mode.SAFE;
        }
        if (changed.isEmpty()) {
            selfCheckWanted = !version.equals(storedVersion);
            LOGGER.info("[FastPose] GeckoLib {}: its posing code is the code our fast posing copied ({} methods and "
                    + "fields the same as in {}); full fast posing{}", version, membersCompared, storedVersion,
                    selfCheckWanted ? ", with a " + SELF_CHECK_SECONDS + " s same-pose self-check once villagers are drawn" : "");
            decided = Mode.FULL;
        } else {
            LOGGER.info("[FastPose] GeckoLib {}: {} of {} watched posing methods or fields differ from {} ({}); safe posing "
                    + "(GeckoLib's own code, handed only the bones the clips move). /villagerfps anim fingerprint lists them.",
                    version, changed.size(), membersCompared, storedVersion, firstFew(changed));
            decided = Mode.SAFE;
        }
        return decided;
    }

    private static String firstFew(List<String> lines) {
        return String.join(", ", lines.subList(0, Math.min(3, lines.size()))) + (lines.size() > 3 ? ", ..." : "");
    }

    private static GeckoFingerprint.Stored readStored() throws IOException {
        try (InputStream in = FastPose.class.getResourceAsStream(STORED_FILE)) {
            if (in == null) throw new IOException("resource " + STORED_FILE + " is missing from the mod jar");
            return GeckoFingerprint.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** A loaded GeckoLib class's own bytes, as in its jar (".class" resources are never hidden by modules), or null. */
    private static byte[] classBytes(String name) {
        try {
            Class<?> type = Class.forName(name, false, FastPose.class.getClassLoader());
            String simple = name.substring(name.lastIndexOf('.') + 1) + ".class";
            try (InputStream in = type.getResourceAsStream(simple)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (ClassNotFoundException | IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** {@code /villagerfps anim fingerprint}: what was compared and what differs. */
    public static List<String> fingerprintReport() {
        mode();
        List<String> lines = new ArrayList<>();
        lines.add("Stored codes: GeckoLib " + storedVersion + ", " + membersCompared + " posing methods and fields ("
                + STORED_FILE + " in the mod jar). Loaded: GeckoLib " + geckoVersion + ".");
        if (changed.isEmpty()) {
            lines.add("All the same: our copied posing gives GeckoLib's pose on this version.");
        } else {
            lines.add(changed.size() + " differ (safe posing is used for them):");
            for (String line : changed) lines.add("  " + line);
        }
        return lines;
    }

    /** {@code /villagerfps anim fingerprint write}: the loaded GeckoLib's codes to a file in {@code directory}. */
    public static String writeFingerprint(Path directory) {
        try {
            Map<String, String> loaded = GeckoFingerprint.all(FastPose::classBytes);
            Path file = directory.resolve("fastpose-fingerprint-" + geckoVersion() + ".txt");
            Files.writeString(file, GeckoFingerprint.format(new GeckoFingerprint.Stored(geckoVersion(), loaded)),
                    StandardCharsets.UTF_8);
            return "Wrote " + loaded.size() + " codes of the loaded GeckoLib " + geckoVersion() + " to " + file
                    + ". Copy it over src/main/resources" + STORED_FILE
                    + " only after the copied posing has been re-based on that GeckoLib.";
        } catch (Throwable e) {
            return "Could not write the fingerprint: " + e;
        }
    }

    /** A GeckoLib field or method the fast posing uses is missing: GeckoLib's own posing for the rest of the session. */
    public static void broken(Throwable cause) {
        if (failed) return;
        failed = true;
        failedWhy = String.valueOf(cause);
        LOGGER.warn("[FastPose] GeckoLib {} does not have what fast villager posing needs; "
                + "back to GeckoLib's own posing until restart.", geckoVersion, cause);
    }

    public static boolean failed() {
        return failed;
    }

    // ---------------------------------------------------------------------------------
    // Safe posing: the bones any loaded clip keys (FastController hands GeckoLib only these)
    // ---------------------------------------------------------------------------------

    private static Map<ResourceLocation, BakedAnimations> keyedSource;
    private static Set<String> keyed = Set.of();

    /** The names of every bone any loaded clip keys; rebuilt when GeckoLib reloads its clips. Render thread. */
    public static Set<String> keyedBones() {
        Map<ResourceLocation, BakedAnimations> all;
        try {
            all = GeckoLibCache.getBakedAnimations();
        } catch (LinkageError e) {
            broken(e);
            return Set.of();
        }
        if (all == keyedSource) return keyed;
        Set<String> names = new HashSet<>();
        for (BakedAnimations baked : all.values()) {
            Map<String, Animation> clips = baked.animations();
            if (clips == null) continue;
            for (Animation clip : clips.values()) {
                BoneAnimation[] bones = clip.boneAnimations();
                if (bones == null) continue;
                for (BoneAnimation bone : bones) names.add(bone.boneName());
            }
        }
        keyed = Set.copyOf(names);
        keyedSource = all;
        return keyed;
    }

    // ---------------------------------------------------------------------------------
    // Reports
    // ---------------------------------------------------------------------------------

    /** One line for {@code /villagerfps}. */
    public static String report() {
        Mode now = mode();
        String gecko = "GeckoLib " + geckoVersion;
        String line = switch (now) {
            case FULL -> "Posing: full fast (our copy of GeckoLib's posing without its waste), " + gecko
                    + fingerprintWord() + selfCheckWord();
            case SAFE -> "Posing: safe fast (GeckoLib's own posing handed only the " + keyedBones().size()
                    + " bones the clips move), " + gecko + fingerprintWord() + selfCheckWord();
            case GECKO -> "Posing: GeckoLib's own, " + gecko
                    + (failed ? " (fast posing failed: " + failedWhy + ", see the log)" : "");
        };
        if (chosen != null && !failed)
            line += " [chosen with /villagerfps anim " + chosen.name().toLowerCase(Locale.ROOT) + "; anim auto lets the game pick]";
        return line;
    }

    private static String fingerprintWord() {
        if (decided == null) return "";
        return changed.isEmpty() ? ", posing code the same as " + storedVersion
                : ", " + changed.size() + " of " + membersCompared + " posing methods/fields differ from "
                        + storedVersion + " (/villagerfps anim fingerprint)";
    }

    private static String selfCheckWord() {
        if (selfCheckRunning) return ", self-check running";
        if (selfCheckPassed) return ", self-check passed";
        if (selfCheckFailed) return ", self-check FAILED (see the log)";
        return "";
    }

    // ---------------------------------------------------------------------------------
    // Same-pose check (/villagerfps anim check, and the self-check): for a while, every
    // short cut also works the answer out GeckoLib's way and counts any difference.
    // Render thread only.
    // ---------------------------------------------------------------------------------

    private static long checkUntil;
    static long lookups, lookupMisses;
    private static long rests, restMisses;
    private static double worstRest;

    /** Start checking for {@code seconds}; counts start again from zero. */
    public static void startCheck(int seconds) {
        lookups = lookupMisses = rests = restMisses = 0;
        worstRest = 0;
        checkUntil = System.nanoTime() + seconds * 1_000_000_000L;
    }

    public static boolean checking() {
        long until = checkUntil;
        if (until == 0) return false;
        if (System.nanoTime() < until) return true;
        checkUntil = 0;
        if (selfCheckRunning) finishSelfCheck();
        return false;
    }

    private static void startSelfCheck() {
        selfCheckWanted = false;
        selfCheckRunning = true;
        startCheck(SELF_CHECK_SECONDS);
        LOGGER.info("[FastPose] self-check started on GeckoLib {}: for {} s every villager drawn is also posed "
                + "GeckoLib's way and compared", geckoVersion, SELF_CHECK_SECONDS);
    }

    private static void finishSelfCheck() {
        selfCheckRunning = false;
        if (lookups + rests == 0) {
            selfCheckWanted = true;          // no villager drawn in that minute: again when one is
            return;
        }
        if (lookupMisses + restMisses > 0) {
            selfCheckFailed = true;
            decided = Mode.SAFE;
            LOGGER.warn("[FastPose] self-check on GeckoLib {}: {} of {} keyframe searches and {} of {} rest bones "
                    + "differed from GeckoLib's own posing (biggest {}); safe posing until restart",
                    geckoVersion, lookupMisses, lookups, restMisses, rests, worstRest);
        } else {
            selfCheckPassed = true;
            LOGGER.info("[FastPose] self-check on GeckoLib {} passed: {} keyframe searches and {} rest bones, "
                    + "none different; full fast posing stays on", geckoVersion, lookups, rests);
        }
    }

    /** A bone left alone because it was already at rest; {@code difference} = what GeckoLib would have moved it. */
    public static void checkedRest(double difference) {
        rests++;
        if (difference != 0) {
            restMisses++;
            worstRest = Math.max(worstRest, Math.abs(difference));
        }
    }

    /** The check's counts so far, for chat. */
    public static String checkReport() {
        String when = checking() ? "Checking (still running): " : "Last check: ";
        return when + lookups + " keyframe searches, " + lookupMisses + " different; "
                + rests + " bones left at rest, " + restMisses + " different"
                + (restMisses > 0 ? String.format(" (biggest %.5f)", worstRest) : "")
                + (lookups + rests == 0 ? ". Nothing checked yet: look at some villagers while it runs." : ".");
    }
}
