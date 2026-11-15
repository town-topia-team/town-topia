package com.alex.test.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The far guard armor: an arm or leg piece drawn as one plain box instead of its
 * four to six panels, once the villager is so small on screen that the armor is
 * drawn stiff anyway ({@link DetailCull#stiffArmor}, {@code /villagerfps armor far on|off}).
 *
 * <p>The box is the outline of the piece's panels (trims left out). Each of its six
 * sides wears the texture of the biggest panel face lying on that side, stretched to
 * fill it, so it keeps the piece's colours. A side the panels mostly leave open (the
 * top of a sleeve, where the arm goes in) is left out like the original: the limb
 * shows there, never a hole; an open wall (a boot's heel) keeps the panels' own faces lying
 * there. About 30 quads a piece become 4 to 7. Pieces that are
 * one panel already (elbow and knee caps) or have tilted panels stay as they are; a
 * stud poking out past its plate (the knob on the upper sleeve) is drawn as it is, on
 * top of the box, so it does not stretch the box past that plate.
 * All built once per baked model, in {@link GuardArmorGeoLayer}.
 */
final class ArmorFarModel {

    /** Positions are in blocks; a model pixel is 0.0625. */
    private static final double EPS = 1e-4;
    /** A side whose panel faces cover less than this share of it is left open. */
    private static final double OPEN_SIDE = 0.5;

    private ArmorFarModel() {}

    /**
     * One box standing in for {@code solid} (a piece's cubes without trims), first, then
     * the studs left out of it, drawn as they are ({@link #takeStud}); or null when the
     * piece should stay as it is: fewer than two cubes, or a tilted one.
     */
    static List<GeoCube> merge(List<GeoCube> solid) {
        if (solid.size() < 2) return null;
        for (GeoCube cube : solid)
            if (tilted(cube)) return null;
        List<GeoCube> shell = new ArrayList<>(solid), studs = new ArrayList<>();
        while (takeStud(shell, studs)) {}
        double[] min = new double[3], max = new double[3];
        if (!bounds(shell, min, max)) return null;

        // One face per side, by side (an open side's is null), then the faces the panels have on
        // an open wall, as authored: the back of a boot's heel, a helmet's nose and cheek guards.
        // Without them an open wall showed what the box hides. An open top or bottom is where the
        // limb or neck goes in; its rim is mostly hidden by the next piece and stays out.
        int sides = Direction.values().length;
        GeoQuad[] faces = new GeoQuad[sides];
        List<GeoQuad> rims = new ArrayList<>();
        boolean any = false;
        for (Direction side : Direction.values()) {
            GeoQuad face = side(shell, side, min, max);
            faces[side.ordinal()] = face;
            any |= face != null;
            if (face == null && side.getAxis() != Direction.Axis.Y) covered(shell, side, min, max, null, rims);
        }
        if (!any) return null;
        GeoQuad[] quads = Arrays.copyOf(faces, sides + rims.size());
        for (int i = 0; i < rims.size(); i++) quads[sides + i] = rims.get(i);
        Vec3 size = new Vec3((max[0] - min[0]) * 16, (max[1] - min[1]) * 16, (max[2] - min[2]) * 16);
        List<GeoCube> out = new ArrayList<>();
        out.add(new GeoCube(quads, Vec3.ZERO, Vec3.ZERO, size, 0, false));
        out.addAll(studs);
        return List.copyOf(out);
    }

    /**
     * A stud poking out past the plate under it (the knob on the outside of the upper
     * sleeve) would stretch the box out to it: the plate's own face is then inside the box,
     * the box's side there is mostly empty and left open, and the arm's skin shows through.
     * So cubes lying wholly outside the others' box on a side that would be left open, when
     * that side is covered without them, are moved from {@code shell} to {@code studs}.
     * True when one side's were.
     */
    private static boolean takeStud(List<GeoCube> shell, List<GeoCube> studs) {
        double[] min = new double[3], max = new double[3];
        if (shell.size() < 3 || !bounds(shell, min, max)) return false;
        for (Direction side : Direction.values()) {
            if (covered(shell, side, min, max) >= OPEN_SIDE) continue;
            int a = side.getAxis().ordinal();
            boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            double at = positive ? max[a] : min[a];
            List<GeoCube> out = new ArrayList<>(), rest = new ArrayList<>();
            for (GeoCube cube : shell) {
                double[] lo = new double[3], hi = new double[3];
                bounds(List.of(cube), lo, hi);
                (Math.abs((positive ? hi[a] : lo[a]) - at) <= EPS ? out : rest).add(cube);
            }
            double[] restMin = new double[3], restMax = new double[3];
            if (rest.size() < 2 || !bounds(rest, restMin, restMax)) continue;
            // A stud: wholly past the others on that side, and standing on their face (inside
            // it the other two ways), not a plate wider than the rest.
            int b = (a + 1) % 3, c = (a + 2) % 3;
            boolean stud = true;
            for (GeoCube cube : out) {
                double[] lo = new double[3], hi = new double[3];
                bounds(List.of(cube), lo, hi);
                stud &= positive ? lo[a] >= restMax[a] - EPS : hi[a] <= restMin[a] + EPS;
                stud &= lo[b] >= restMin[b] - EPS && hi[b] <= restMax[b] + EPS
                        && lo[c] >= restMin[c] - EPS && hi[c] <= restMax[c] + EPS;
            }
            if (!stud || covered(rest, side, restMin, restMax) < OPEN_SIDE) continue;
            shell.clear();
            shell.addAll(rest);
            studs.addAll(out);
            return true;
        }
        return false;
    }

    /** The outline of {@code cubes}' corners into {@code min}/{@code max}; false when they have none. */
    private static boolean bounds(List<GeoCube> cubes, double[] min, double[] max) {
        Arrays.fill(min, Double.MAX_VALUE);
        Arrays.fill(max, -Double.MAX_VALUE);
        for (GeoCube cube : cubes)
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                for (GeoVertex v : quad.vertices()) grow(min, max, v.position());
            }
        return min[0] <= max[0];
    }

    /**
     * A helmet's or chestplate's crowd stand-in ({@link StandIn}): its upright panels
     * ({@code solid}, trims left out) as one box, its tilted ones as they are. With
     * {@code keepBiggest} the biggest panel stays apart: the helmet's cap, so the brim over the
     * forehead stays while the face, mostly open, is left open. Null when nothing merges.
     */
    static List<GeoCube> standIn(List<GeoCube> solid, boolean keepBiggest) {
        List<GeoCube> upright = new ArrayList<>(), tilted = new ArrayList<>();
        for (GeoCube cube : solid)
            (tilted(cube) ? tilted : upright).add(cube);
        GeoCube kept = null;
        if (keepBiggest) {
            for (GeoCube cube : upright)
                if (kept == null || volume(cube) > volume(kept)) kept = cube;
            if (kept != null) upright.remove(kept);
        }
        List<GeoCube> box = merge(upright);
        if (box == null) return null;
        List<GeoCube> out = new ArrayList<>();
        if (kept != null) out.add(kept);
        out.addAll(box);
        out.addAll(tilted);
        return List.copyOf(out);
    }

    private static double volume(GeoCube cube) {
        Vec3 size = cube.size();
        return size.x * size.y * size.z;
    }

    /**
     * The trims still worth drawing over {@code box} (from {@link #merge}) while trims are on:
     * tilted ones, or any poking out of it, whole; of the rest, their faces lying on a wall the
     * box leaves open (the strap across a boot's heel), as one more cube.
     */
    static List<GeoCube> outside(List<GeoCube> trims, GeoCube box) {
        double[] min = new double[3], max = new double[3];
        bounds(List.of(box), min, max);
        List<GeoCube> out = new ArrayList<>(), inside = new ArrayList<>();
        for (GeoCube trim : trims) {
            boolean poking = tilted(trim);
            for (GeoQuad quad : trim.quads()) {
                if (poking || quad == null) continue;
                for (GeoVertex v : quad.vertices()) {
                    Vector3f p = v.position();
                    for (int a = 0; a < 3; a++) {
                        if (p.get(a) < min[a] - EPS || p.get(a) > max[a] + EPS) poking = true;
                    }
                }
            }
            (poking ? out : inside).add(trim);
        }
        List<GeoQuad> rims = new ArrayList<>();
        for (Direction side : Direction.values())
            if (box.quads()[side.ordinal()] == null && side.getAxis() != Direction.Axis.Y)
                covered(inside, side, min, max, null, rims);
        // The box's size, so it counts as solid for the crowd's back faces and no normal is flipped.
        if (!rims.isEmpty())
            out.add(new GeoCube(rims.toArray(new GeoQuad[0]), Vec3.ZERO, Vec3.ZERO, box.size(), 0, false));
        return out;
    }

    /** Quads GeckoLib will draw for these cubes (a far box leaves its open sides out). */
    static int quads(List<GeoCube> cubes) {
        int n = 0;
        for (GeoCube cube : cubes)
            for (GeoQuad quad : cube.quads())
                if (quad != null) n++;
        return n;
    }

    /**
     * The box's face on {@code side}: the biggest panel face lying there, its corners
     * moved out to the box's corners (texture kept), or null when the panels leave
     * most of that side open.
     */
    private static GeoQuad side(List<GeoCube> solid, Direction side, double[] min, double[] max) {
        int a = side.getAxis().ordinal();
        boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double at = positive ? max[a] : min[a];
        int b = (a + 1) % 3, c = (a + 2) % 3;

        GeoQuad[] biggest = new GeoQuad[1];
        if (covered(solid, side, min, max, biggest, null) < OPEN_SIDE) return null;
        GeoQuad best = biggest[0];

        GeoVertex[] from = best.vertices();
        double fromMinB = Double.MAX_VALUE, fromMinC = Double.MAX_VALUE;
        for (GeoVertex v : from) {
            fromMinB = Math.min(fromMinB, v.position().get(b));
            fromMinC = Math.min(fromMinC, v.position().get(c));
        }
        GeoVertex[] corners = new GeoVertex[from.length];
        for (int i = 0; i < from.length; i++) {
            Vector3f p = from[i].position();
            Vector3f moved = new Vector3f();
            moved.setComponent(a, (float) at);
            moved.setComponent(b, (float) (Math.abs(p.get(b) - fromMinB) <= EPS ? min[b] : max[b]));
            moved.setComponent(c, (float) (Math.abs(p.get(c) - fromMinC) <= EPS ? min[c] : max[c]));
            corners[i] = new GeoVertex(moved, from[i].texU(), from[i].texV());
        }
        return new GeoQuad(corners, new Vector3f(best.normal()), best.direction());
    }

    /** The share of the box's {@code side} that panel faces lying there cover. */
    private static double covered(List<GeoCube> solid, Direction side, double[] min, double[] max) {
        return covered(solid, side, min, max, null, null);
    }

    /**
     * The same; when given, the biggest of those faces into {@code biggest[0]} (null when there
     * is none) and every one of them into {@code lying}.
     */
    private static double covered(List<GeoCube> solid, Direction side, double[] min, double[] max,
                                  GeoQuad[] biggest, List<GeoQuad> lying) {
        int a = side.getAxis().ordinal();
        boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double at = positive ? max[a] : min[a];
        int b = (a + 1) % 3, c = (a + 2) % 3;

        GeoQuad best = null;
        double bestArea = 0, covered = 0;
        for (GeoCube cube : solid) {
            double cubeMin = Double.MAX_VALUE, cubeMax = -Double.MAX_VALUE;
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                for (GeoVertex v : quad.vertices()) {
                    cubeMin = Math.min(cubeMin, v.position().get(a));
                    cubeMax = Math.max(cubeMax, v.position().get(a));
                }
            }
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                GeoVertex[] v = quad.vertices();
                double here = v[0].position().get(a);
                if (Math.abs(here - at) > EPS || !flatAlong(v, a)) continue;
                // Which way it faces: the cube's far end on that axis is its outer face.
                boolean facesUp = cubeMax - cubeMin > EPS ? Math.abs(here - cubeMax) <= EPS : quad.normal().get(a) > 0;
                if (facesUp != positive) continue;
                double area = extent(v, b) * extent(v, c);
                covered += area;
                if (lying != null && area > EPS * EPS) lying.add(quad);
                if (area > bestArea) {
                    bestArea = area;
                    best = quad;
                }
            }
        }
        if (biggest != null) biggest[0] = best;
        double sideArea = (max[b] - min[b]) * (max[c] - min[c]);
        return best == null || sideArea <= 0 ? 0 : covered / sideArea;
    }

    private static boolean tilted(GeoCube cube) {
        Vec3 r = cube.rotation();
        return Math.abs(r.x) > 1e-6 || Math.abs(r.y) > 1e-6 || Math.abs(r.z) > 1e-6;
    }

    private static boolean flatAlong(GeoVertex[] v, int a) {
        for (GeoVertex corner : v)
            if (Math.abs(corner.position().get(a) - v[0].position().get(a)) > EPS) return false;
        return true;
    }

    private static double extent(GeoVertex[] v, int axis) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (GeoVertex corner : v) {
            lo = Math.min(lo, corner.position().get(axis));
            hi = Math.max(hi, corner.position().get(axis));
        }
        return hi - lo;
    }

    private static void grow(double[] min, double[] max, Vector3f p) {
        for (int a = 0; a < 3; a++) {
            min[a] = Math.min(min[a], p.get(a));
            max[a] = Math.max(max[a], p.get(a));
        }
    }
}
