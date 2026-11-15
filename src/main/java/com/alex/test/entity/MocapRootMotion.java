package com.alex.test.entity;

import com.alex.test.DynamicVillages;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Where each mocap clip carries the villager, one entry per tick.
 *
 * <p>The retarget strips the performer's translation on purpose — a Minecraft entity
 * is placed and aimed by the game, and a clip that wandered off would fight it. The
 * cost is that the feet skate: the fencing take is a lot of footwork and none of it
 * goes anywhere. So the travel is put back on the entity instead of in the clip.
 * {@code tools/mocap/root_motion.py} works out which foot is planted each frame and
 * integrates the movement that would hold it still; walking the entity along that
 * while the clip plays cancels the slide (measured: the planted foot's drift falls
 * from 0.83 to 0.22 px/tick at its worst, in the fencing take).
 *
 * <p>Steps are in the model's own frame — {@code +x} is the villager's right,
 * {@code -z} is forwards — so they have to be rotated by its yaw before use.
 *
 * <p>Read straight off the classpath rather than through the resource manager: this
 * is baked data that ships with the jar, and it has to be readable on the server,
 * where {@code assets/} is not reloaded.
 */
public final class MocapRootMotion {

    /**
      * Deliberately not under {@code assets/dynamicvillages/animations/}. GeckoLib loads
      * every {@code .json} in that folder as an animation file, and one that is not one
      * does not merely fail on its own: {@code GeckoLibCache.loadAnimations} lets a
      * non-{@code CompoundException} escape the per-file catch, which fails the whole
      * reload future, which leaves {@code ANIMATIONS} as the empty map it was
      * initialised to. Every animation in the game then silently stops -- the symptom
      * is "Unable to find animation: walk", for every entity, with nothing logged about
      * the file that actually caused it.
      */
     private static final String PATH = "/assets/" + DynamicVillages.MODID + "/mocap/mocap_root.json";

    private static Map<String, double[][]> clips;

    private MocapRootMotion() {}

    private static synchronized Map<String, double[][]> clips() {
        if (clips != null)
            return clips;

        Map<String, double[][]> loaded = new HashMap<>();
        try (InputStream in = MocapRootMotion.class.getResourceAsStream(PATH)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    var steps = entry.getValue().getAsJsonObject().getAsJsonArray("step");
                    double[][] path = new double[steps.size()][2];

                    for (int i = 0; i < steps.size(); i++) {
                        var xz = steps.get(i).getAsJsonArray();
                        path[i][0] = xz.get(0).getAsDouble();
                        path[i][1] = xz.get(1).getAsDouble();
                    }

                    loaded.put(entry.getKey(), path);
                }
            }
        }
        catch (Exception ex) {
            // A missing or malformed table only costs the root motion, so the clip
            // still plays -- in place, the way it did before any of this existed.
            com.mojang.logging.LogUtils.getLogger().error("Could not read {}", PATH, ex);
        }

        return clips = loaded;
    }

    /**
     * This tick's movement for a clip, in the model's frame, or {@link Vec3#ZERO} if
     * the clip has no path. {@code tick} wraps, so a looping clip keeps stepping.
     */
    public static Vec3 step(String clip, int tick) {
        double[][] path = clips().get(clip);
        if (path == null || path.length == 0)
            return Vec3.ZERO;

        double[] xz = path[Math.floorMod(tick, path.length)];

        return new Vec3(xz[0], 0, xz[1]);
    }
}
