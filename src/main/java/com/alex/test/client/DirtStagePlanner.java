package com.alex.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Works out which dirt stage each mud pixel of a skin belongs to, so the
 * in-between stages can be built from just a clean skin and its dirty twin.
 *
 * <p>The mud pixels are the ones that differ between the two images. They are
 * grouped into splotches -- touching pixels on the same face of the model --
 * so every painted mark appears whole, never as speckles. Each splotch is
 * ranked by how high it sits on the body in the rest pose, read from the rig's
 * per-face UVs, and mud climbs from the boots up:
 * <table border="1">
 *   <caption>Stage cut-offs, as a share of body height</caption>
 *   <tr><th>stage</th><th>splotch height below</th><th>on the base_villager rig</th></tr>
 *   <tr><td>1</td><td>{@value #BOOTS_TOP}</td><td>boots, lower shins</td></tr>
 *   <tr><td>2</td><td>{@value #HANDS_TOP}</td><td>shins, thighs, hands, overall hems</td></tr>
 *   <tr><td>3</td><td>{@value #TORSO_TOP}</td><td>overalls, forearms, sleeves</td></tr>
 *   <tr><td>4</td><td>everything else</td><td>chest, face, hat</td></tr>
 * </table>
 * A pixel that no face of the rig shows (spare atlas space) goes to stage 4.
 *
 * <p>Kept free of Minecraft types apart from Gson.
 */
public final class DirtStagePlanner {

    static final double BOOTS_TOP = 0.15;
    static final double HANDS_TOP = 0.40;
    static final double TORSO_TOP = 0.65;

    /**
     * Bones never on a farmer: the held-item plane, the bard's instruments and outfit, the miner's gear,
     * and every other profession's accessories ({@code acc_*}, see VillagerGeoModel#ACCESSORY_BONES).
     * Their UVs overlap the skin, and they would drag its heights around.
     */
    private static final Set<String> IGNORED_BONES = Set.of("ITEM", "bard_violin", "bard_bow", "bard_flute", "shield",
            "bard_hat", "bard_cavalier", "bard_cap", "bard_wreath",
            "bard_left_puff", "bard_right_puff", "bard_ruff", "bard_lute",
            "miner_cap", "miner_rope",
            "miner_goggles", "miner_lantern", "miner_slouch", "miner_beard", "miner_sack");

    private static final String[] SIDE_FACES = {"north", "east", "south", "west"};

    private DirtStagePlanner() {}

    /**
     * Rest-pose height of every texel of the rig's texture, from its per-face UVs.
     *
     * @param heights model-space Y per texel (row-major); NaN where no face shows it
     * @param faces   which face covers each texel, for keeping splotches to one face; -1 for none
     */
    public record TexelHeights(int width, int height, float[] heights, int[] faces, float bodyHeight) {

        /** Height of the texel under image pixel (x, y) of an image {@code w} x {@code h}. */
        float heightAt(int x, int y, int w, int h) {
            return heights[index(x, y, w, h)];
        }

        int faceAt(int x, int y, int w, int h) {
            return faces[index(x, y, w, h)];
        }

        /** Images may be larger than the UV grid (a high-resolution pack); scale down onto it. */
        private int index(int x, int y, int w, int h) {
            int tx = Math.min(width - 1, x * width / w);
            int ty = Math.min(height - 1, y * height / h);
            return ty * width + tx;
        }
    }

    /**
     * Reads the heights from a Bedrock-format geo file with per-face UVs.
     * Returns null when the file does not have the expected shape; the caller
     * then skips generating stages.
     */
    @Nullable
    public static TexelHeights fromGeo(JsonObject geo) {
        try {
            JsonObject geometry = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            int texW = description.get("texture_width").getAsInt();
            int texH = description.get("texture_height").getAsInt();
            if (texW <= 0 || texH <= 0) return null;

            JsonArray bones = geometry.getAsJsonArray("bones");
            Map<String, String> parents = new HashMap<>();
            for (JsonElement element : bones) {
                JsonObject bone = element.getAsJsonObject();
                String parent = bone.has("parent") ? bone.get("parent").getAsString() : null;
                parents.put(bone.get("name").getAsString(), parent);
            }

            float[] heights = new float[texW * texH];
            int[] faces = new int[texW * texH];
            Arrays.fill(heights, Float.NaN);
            Arrays.fill(faces, -1);
            float bodyHeight = 0f;
            int faceId = 0;
            for (JsonElement element : bones) {
                JsonObject bone = element.getAsJsonObject();
                if (isIgnored(bone.get("name").getAsString(), parents) || !bone.has("cubes")) continue;
                for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                    JsonObject cube = cubeElement.getAsJsonObject();
                    // Box UVs (an array) are not used by this rig; skip rather than guess.
                    if (!cube.has("uv") || !cube.get("uv").isJsonObject()) continue;
                    float bottom = cube.getAsJsonArray("origin").get(1).getAsFloat();
                    float top = bottom + cube.getAsJsonArray("size").get(1).getAsFloat();
                    bodyHeight = Math.max(bodyHeight, top);
                    JsonObject uv = cube.getAsJsonObject("uv");
                    for (String side : SIDE_FACES) {
                        paintFace(uv, side, texW, texH, heights, faces, faceId++, top, bottom, true);
                    }
                    paintFace(uv, "up", texW, texH, heights, faces, faceId++, top, top, false);
                    paintFace(uv, "down", texW, texH, heights, faces, faceId++, bottom, bottom, false);
                }
            }
            return bodyHeight > 0f ? new TexelHeights(texW, texH, heights, faces, bodyHeight) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Writes one face's heights into its UV rectangle. A side face runs from
     * {@code top} along its first row to {@code bottom} along its last (flipped
     * when its UV height is negative); up and down faces are flat. Where faces
     * share texels the lowest wins: mud there shows first on the lowest one.
     */
    private static void paintFace(JsonObject uv, String name, int texW, int texH,
                                  float[] heights, int[] faces, int faceId,
                                  float top, float bottom, boolean vertical) {
        if (!uv.has(name)) return;
        JsonObject face = uv.getAsJsonObject(name);
        JsonArray origin = face.getAsJsonArray("uv");
        JsonArray size = face.getAsJsonArray("uv_size");
        float u = origin.get(0).getAsFloat(), v = origin.get(1).getAsFloat();
        float w = size.get(0).getAsFloat(), h = size.get(1).getAsFloat();
        int cols = Math.round(Math.abs(w)), rows = Math.round(Math.abs(h));
        if (cols == 0 || rows == 0) return;
        int u0 = Math.round(Math.min(u, u + w));
        int v0 = Math.round(Math.min(v, v + h));
        for (int row = 0; row < rows; row++) {
            int ty = v0 + row;
            if (ty < 0 || ty >= texH) continue;
            float t = (row + 0.5f) / rows;
            // Row 0 is the top of a side face, unless the UV is flipped.
            float y = !vertical ? top : h > 0 ? top - t * (top - bottom) : bottom + t * (top - bottom);
            for (int col = 0; col < cols; col++) {
                int tx = u0 + col;
                if (tx < 0 || tx >= texW) continue;
                int i = ty * texW + tx;
                if (Float.isNaN(heights[i]) || y < heights[i]) heights[i] = y;
                if (faces[i] < 0) faces[i] = faceId;
            }
        }
    }

    private static boolean isIgnored(String bone, Map<String, String> parents) {
        for (String b = bone; b != null; b = parents.get(b)) {
            if (IGNORED_BONES.contains(b) || b.startsWith("acc_")) return true;
        }
        return false;
    }

    /**
     * The stage at which each pixel turns from clean to dirty: 0 for pixels
     * the two images agree on, 1 to 4 for mud. Pixels are packed colours in
     * any one consistent format (alpha changes count as mud too).
     */
    public static int[] stagePerPixel(int[] clean, int[] dirty, int w, int h, TexelHeights heights) {
        int[] stage = new int[w * h];
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || clean[start] == dirty[start]) continue;
            // Flood one splotch: touching mud pixels on the same face.
            int face = heights.faceAt(start % w, start / w, w, h);
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            double sum = 0;
            int covered = 0;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w, y = p / w;
                float py = heights.heightAt(x, y, w, h);
                if (!Float.isNaN(py)) {
                    sum += py;
                    covered++;
                }
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int n = ny * w + nx;
                    if (seen[n] || clean[n] == dirty[n] || heights.faceAt(nx, ny, w, h) != face) continue;
                    seen[n] = true;
                    queue[tail++] = n;
                }
            }
            int splotchStage = covered == 0 ? 4 : stageForHeight(sum / covered / heights.bodyHeight());
            for (int i = 0; i < tail; i++) stage[queue[i]] = splotchStage;
        }
        return stage;
    }

    static int stageForHeight(double share) {
        if (share < BOOTS_TOP) return 1;
        if (share < HANDS_TOP) return 2;
        if (share < TORSO_TOP) return 3;
        return 4;
    }
}
