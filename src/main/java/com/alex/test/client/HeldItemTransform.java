package com.alex.test.client;

import com.alex.test.entity.NpcEntity;

import java.util.List;
import java.util.Locale;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * Where a villager's held item sits on its arm, per rig, and per animation.
 *
 * <p>The item is anchored to a point on the <em>upper</em> arm's limb axis, near the
 * hand end, and carried through {@link BentLimb}'s deformation -- see
 * {@link BentLimb#handGrip}. It used to hang off the forearm bone instead, and on
 * {@code base_villager} that bone's pivot sits at the shoulder rather than the elbow,
 * so the item hinged 6.4px away from where the drawn arm hinges: measured off the clip
 * data, the axe wandered 7.4px across {@code rok_chop} and 7.5px across
 * {@code rok_mine}, which on a 4px arm reads as the villager juggling it.
 *
 * <p>So the three offsets are in the limb's own frame, not the bone's:
 * <ul>
 *   <li>{@code offsetY} -- pixels back up the arm from the fingertips;</li>
 *   <li>{@code offsetX} -- pixels off the limb axis, across the arm;</li>
 *   <li>{@code offsetZ} -- pixels forward of it.</li>
 * </ul>
 * The rotation is unchanged in meaning: at the hand end the sweep has turned through
 * the whole joint angle, so the frame the rotation is applied in is the same one the
 * forearm bone gave before.
 *
 * <p>The values are deliberately mutable and client-only: {@code /helditem} edits
 * them while the game runs so a placement can be dialled in against the actual
 * animation. Once it looks right, copy the printed numbers back into the
 * constructor calls below so they survive a restart.
 */
public final class HeldItemTransform implements Tunable {

    /**
     * A per-tool nudge, in the same frame as the offsets below, added on top of the
     * rig's placement.
     *
     * <p>Keyed by <em>item</em>, not by animation. A sword that sits wrong sits wrong in
     * every clip that holds one, because what is wrong is the shape of the item against
     * the shape of the fist; keyed to clip names instead, it had to be fixed once per
     * animation and the next animation added would miss out. The tag lookup in
     * {@link #adjustFor} gets a modded sword the sword treatment for free.
     *
     * <p>Bread deliberately has no entry: it sits correctly on the bare placement, and
     * an adjustment that exists only to be zero is a trap for whoever tunes it next.
     */
    public static final class Adjust implements Tunable {
        private final String name;
        private final float[] defaults;
        private float side, up, forward, rotX, rotY, rotZ, scale;

        private Adjust(String name, float side, float up, float forward,
                       float rotX, float rotY, float rotZ, float scale) {
            this.name = name;
            this.defaults = new float[] {side, up, forward, rotX, rotY, rotZ, scale};
            reset();
        }

        @Override public String name() { return name; }
        @Override public float offsetX() { return side; }
        @Override public float offsetY() { return up; }
        @Override public float offsetZ() { return forward; }
        @Override public float rotX() { return rotX; }
        @Override public float rotY() { return rotY; }
        @Override public float rotZ() { return rotZ; }
        @Override public float scale() { return scale; }

        @Override public void setOffset(float x, float y, float z) { side = x; up = y; forward = z; }
        @Override public void addOffset(float x, float y, float z) { setOffset(side + x, up + y, forward + z); }
        @Override public void setRotation(float x, float y, float z) { rotX = x; rotY = y; rotZ = z; }
        @Override public void addRotation(float x, float y, float z) { setRotation(rotX + x, rotY + y, rotZ + z); }
        @Override public void setScale(float value) { scale = value; }

        @Override public void reset() {
            setOffset(defaults[0], defaults[1], defaults[2]);
            setRotation(defaults[3], defaults[4], defaults[5]);
            setScale(defaults[6]);
        }

        @Override public String describe() {
            return String.format(Locale.ROOT,
                    "%s  offset %s %s %s  rotation %s %s %s  scale %s",
                    name, fmt(side), fmt(up), fmt(forward),
                    fmt(rotX), fmt(rotY), fmt(rotZ), fmt(scale));
        }
    }

    /**
     * Dialled in against the running game with {@code /helditem}, not derived. Every one
     * of the tools wanted the same +1 up the arm on top of the placement, which says the
     * placement itself is a pixel low for anything held by a handle -- it was calibrated
     * on a loaf of bread, which is gripped in the middle.
     */
    public static final Adjust SWORD = new Adjust("sword", -1.5f, 1f, 0f, 0f, 0f, 0f, 1f);

    /** Axes, and the other handle-and-head tools that share the {@code handheld} model. */
    public static final Adjust AXE = new Adjust("axe", -2f, 1f, 0f, 0f, 0f, 0f, 1f);
    public static final Adjust PICKAXE = new Adjust("pickaxe", -2f, 1f, 0f, 0f, 0f, 0f, 1f);
    public static final Adjust SHOVEL = new Adjust("shovel", -2f, 1f, 0f, 0f, 0f, 0f, 1f);

    /**
     * The till stroke gets the hand to 10.3px off the floor and no lower -- the fold is
     * in the torso, not the arm -- so a default-size hoe stops about 4px short of the
     * ground it is supposed to be working, and a longer one closes that. Rolled round the
     * arm so the shaft angles away from the villager rather than into it.
     */
    public static final Adjust HOE = new Adjust("hoe", -1f, 1f, -1f, 0f, -30f, 0f, 1.45f);

    /**
     * Everything that is not a tool: bread, blocks, whatever else ends up in a hand.
     *
     * <p>This used to be a private zero that nothing could reach, on the reasoning that
     * an adjustment existing only to be zero is a trap. That was wrong in the way that
     * matters -- it left the one item whose placement could not be tuned at all, which is
     * exactly the one that turned out to still need tuning. A knob at zero is not a trap;
     * a missing knob is.
     */
    public static final Adjust OTHER = new Adjust("other", 0f, 0f, 0f, 0f, 0f, 0f, 1f);

    /**
     * Fine-tuning on top of the {@code shield} bone in the model, which is where the
     * shield is really placed (move it in Blockbench, see {@link ShieldGeoLayer}).
     * Zero by default; {@code /helditem shield} nudges it live.
     */
    public static final Adjust SHIELD = new Adjust("shield", 0f, 0f, 0f, 0f, 0f, 0f, 1f);

    /**
     * The bow while an archer aims (the AIM clip). Unlike the others this is in the
     * villager's body frame, not the arm's: offsets are pixels right / up / forward
     * of the bow hand, and the bow is turned by rotation Y, then X, then Z.
     *
     * <p>Worked out from the flat bow sprite: its stave runs corner to corner, so Z -45
     * stands it upright; Y 90 turns the string toward the archer's face. Forward -3.8
     * puts the hand on the grip (4.2 px out from the sprite's centre, at scale 0.9)
     * rather than on the string, measured from the middle of the fist
     * ({@code HeldItemGeoLayer.AIMED_BOW_GRIP_UP}). Side -3 (toward the archer's left,
     * the drawing hand's side) per the author, 10-03. Nudge with {@code /helditem bowaim}.
     */
    public static final Adjust BOW_AIM = new Adjust("bowaim", -3f, 0f, -3.8f, 0f, 90f, -45f, 0.9f);

    /**
     * The bow while an archer stands or walks. All zero is exactly a player's hold
     * (vanilla's hand placement and the bow's third-person transform). Offsets are
     * pixels right / up the arm / forward in the forearm's frame; rotation Y, then X,
     * then Z turns it in the player's hand frame. Nudge with {@code /helditem bowcarry}.
     */
    public static final Adjust BOW_CARRY = new Adjust("bowcarry", 0f, 0f, 0f, 0f, 0f, 0f, 1f);

    /**
     * The Smith's Hammer drawing out a bar at the anvil (smith_anvil_draw), held like the bow above: all
     * zero is a player's hold, which the clip was solved for in tools/anvil. A nudge here moves the hammer
     * off the bar the sketch aimed at; tune in the sketch and re-bake instead. {@code /helditem smithhammer}.
     */
    public static final Adjust SMITH_HAMMER_HOLD = new Adjust("smithhammer", 0f, 0f, 0f, 0f, 0f, 0f, 1f);

    private static final List<Adjust> ADJUSTMENTS =
            List.of(SWORD, AXE, PICKAXE, SHOVEL, HOE, OTHER, SHIELD, BOW_AIM, BOW_CARRY, SMITH_HAMMER_HOLD);

    /** The adjustment for whatever is being held, chosen by the item's own tag. */
    public static Adjust adjustFor(ItemStack stack) {
        if (stack.is(ItemTags.SWORDS)) return SWORD;
        if (stack.is(ItemTags.AXES)) return AXE;
        if (stack.is(ItemTags.PICKAXES)) return PICKAXE;
        if (stack.is(ItemTags.SHOVELS)) return SHOVEL;
        if (stack.is(ItemTags.HOES)) return HOE;
        return OTHER;
    }

    /** Every adjustment, for the {@code /helditem} command to offer. */
    public static List<Adjust> tools() { return ADJUSTMENTS; }

    /**
     * Right arm ({@code right_arm} -> {@code bone2}).
     *
     * <p>The upper bone, not the forearm and emphatically not {@code ITEM}: the sweep
     * that draws the limb is built in the upper bone's frame, so that is the frame in
     * which a grip can be pinned to the hand.
     *
     * <p>3.4px below the fingertips and dead on the axis. That looks like an odd place
     * to anchor a grip, and it is: the anchor is not where the item lands. Vanilla's
     * own third-person transform translates a held item 4px along its local y before
     * drawing it, which for a player runs away down the blade and here runs straight
     * back up the forearm -- measured on a rest frame, a loaf anchored at the fingertips
     * rendered with its centre at model y 15.4, the middle of the forearm. The offset
     * cancels that, so what is *drawn* sits in the fist. GeckoLib bakes model x
     * negated, so the old {@code bone2} pivot {@code (-1, 22, 0)} plus {@code (3,
     * -10.5, 0)} landed at {@code (4, 11.5, 0)} while the forearm's axis runs at
     * {@code x 6} -- 2px to the *inboard* side of the arm, the side the torso is on.
     * That is why the hoe disappeared behind the body on the till, whose torso folds
     * 53 degrees: the tool was hung on the body side of the fist. On the axis it is in
     * the middle of the hand, which is where a grip belongs.
     */
    public static final HeldItemTransform BASE_VILLAGER =
            new HeldItemTransform("base_villager", "right_arm", 0f, -3.4f, 0f, -90f, 90f, 270f, 0.8f);

    /**
     * Left arm ({@code left_arm} -> {@code bone}).  Same placement this layer has
     * always used -- 5px up from the fingertips and 6.4px forward of the arm -- now
     * expressed against the limb instead of against the forearm's pivot, which on this
     * rig happens to sit on the elbow and so gave the same point at rest.
     */
    public static final HeldItemTransform DYNAMIC_VILLAGERS =
            new HeldItemTransform("dynamic_villagers", "left_arm", 0f, 5f, -6.4f, 0f, 0f, 180f, 0.8f);

    private static final List<HeldItemTransform> PROFILES = List.of(BASE_VILLAGER, DYNAMIC_VILLAGERS);

    private final String rig;
    private final String defaultBone;
    private final float[] defaults;

    private String bone;
    private float offsetX, offsetY, offsetZ;
    private float rotX, rotY, rotZ;
    private float scale;

    private HeldItemTransform(String rig, String bone,
                              float offsetX, float offsetY, float offsetZ,
                              float rotX, float rotY, float rotZ, float scale) {
        this.rig = rig;
        this.defaultBone = bone;
        this.defaults = new float[] {offsetX, offsetY, offsetZ, rotX, rotY, rotZ, scale};
        reset();
    }

    /** The placement in use for {@code entity}, chosen by the rig it renders on. */
    public static HeldItemTransform forEntity(NpcEntity entity) {
        return entity.usesBaseVillagerRig() ? BASE_VILLAGER : DYNAMIC_VILLAGERS;
    }

    public static List<HeldItemTransform> profiles() { return PROFILES; }

    @Override public String name() { return rig; }

    /** Rig name, as used by the {@code /helditem} command. */
    public String rig() { return rig; }

    /** Bone this rig hangs the held item from. */
    public String bone() { return bone; }

    /**
     * Re-anchors the item to a different bone. Only bones that are rigid relative to
     * the hand are sensible here — anything the animations keyframe independently
     * (such as {@code ITEM}) will carry the item along with it.
     */
    public void setBone(String bone) {
        this.bone = bone;
        NpcGeoRenderer.boneUseChanged();             // HeldItemGeoLayer now draws on another bone
    }

    /** Offset from the fist along the limb, in model units (1/16 of a block). */
    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }
    public float offsetZ() { return offsetZ; }

    /** Rotation applied after the offset, in degrees, in X→Y→Z order. */
    public float rotX() { return rotX; }
    public float rotY() { return rotY; }
    public float rotZ() { return rotZ; }

    public float scale() { return scale; }

    public void setOffset(float x, float y, float z) {
        offsetX = x; offsetY = y; offsetZ = z;
    }

    public void addOffset(float x, float y, float z) {
        setOffset(offsetX + x, offsetY + y, offsetZ + z);
    }

    public void setRotation(float x, float y, float z) {
        rotX = x; rotY = y; rotZ = z;
    }

    public void addRotation(float x, float y, float z) {
        setRotation(rotX + x, rotY + y, rotZ + z);
    }

    public void setScale(float scale) { this.scale = scale; }

    public void reset() {
        setBone(defaultBone);
        setOffset(defaults[0], defaults[1], defaults[2]);
        setRotation(defaults[3], defaults[4], defaults[5]);
        setScale(defaults[6]);
    }

    /** One-line summary in the same argument order the command takes. */
    public String describe() {
        return String.format(Locale.ROOT,
                "%s [bone %s]  offset %s %s %s  rotation %s %s %s  scale %s",
                rig, bone,
                fmt(offsetX), fmt(offsetY), fmt(offsetZ),
                fmt(rotX), fmt(rotY), fmt(rotZ), fmt(scale));
    }

    private static String fmt(float value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value)
                : String.format(Locale.ROOT, "%.3f", value);
    }
}
