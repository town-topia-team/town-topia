package com.alex.test.animation;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.Direction.Axis;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.BoneAnimationQueue;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.animation.keyframe.KeyframeStack;
import software.bernie.geckolib.animation.keyframe.event.CustomInstructionKeyframeEvent;
import software.bernie.geckolib.animation.keyframe.event.ParticleKeyframeEvent;
import software.bernie.geckolib.animation.keyframe.event.SoundKeyframeEvent;
import software.bernie.geckolib.animation.keyframe.event.data.CustomInstructionKeyframeData;
import software.bernie.geckolib.animation.keyframe.event.data.KeyFrameData;
import software.bernie.geckolib.animation.keyframe.event.data.ParticleKeyframeData;
import software.bernie.geckolib.animation.keyframe.event.data.SoundKeyframeData;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.math.MathParser;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.math.MolangQueries;
import software.bernie.geckolib.loading.math.value.Constant;
import software.bernie.geckolib.model.GeoModel;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * An animation track (GeckoLib {@link AnimationController}) that gives GeckoLib 4.8.4's pose
 * without its per-frame waste (plain villager FPS plan, V1). {@link #process} is GeckoLib's own,
 * copied, with three changes that leave every number the same:
 * <ul>
 *   <li>GeckoLib throws away and rebuilds a queue for every bone of the model (74, with nine
 *       lists each) every frame for every playing track. Here each bone keeps one queue, emptied
 *       and handed out only when the clip keys that bone (about a dozen).</li>
 *   <li>A stopped track kept last frame's 74 emptied queues, which the posing step then polled
 *       nine times each, every frame, forever. Here a stopped track has none.</li>
 *   <li>GeckoLib finds the key under the playhead by walking the channel from its first key.
 *       Here the walk starts where it ended last frame, when nothing before that point can be
 *       the answer (same sums in the same order, so the same doubles).</li>
 * </ul>
 * Off ({@link FastPose#on} false) it is GeckoLib's own controller. A GeckoLib piece missing at
 * run time turns fast posing off ({@link FastPose#broken}) instead of crashing.
 */
public class FastController<T extends GeoAnimatable> extends AnimationController<T> {
    private static final Logger LOGGER = LogUtils.getLogger();

    // GeckoLib keeps these two private; while fast, this copy of process keeps its own.
    private boolean justStopped = true;
    private final Set<KeyFrameData> executedKeyFrames = new ObjectOpenHashSet<>();

    /** One queue per bone name, emptied and handed out to boneAnimationQueues when a clip keys that bone. */
    private final Map<String, BoneAnimationQueue> queuePool = new Object2ObjectOpenHashMap<>();

    /** The clip time Molang's query.anim_time reads, in seconds (GeckoLib: a new lambda per frame). */
    private double animTimeNow;
    private final DoubleSupplier animTimeReader = () -> this.animTimeNow;

    /** The clip the search memory below belongs to. */
    @Nullable
    private Animation searchClip;
    /** Per channel (bone index x 9 + channel): the key the last search stopped at. */
    private int[] searchIndex = new int[0];
    /** Per channel: the keys' lengths summed before {@link #searchIndex}, in GeckoLib's order. */
    private double[] searchBefore = new double[0];
    /** Per channel: the latest end time of any key before {@link #searchIndex}. */
    private double[] searchPassed = new double[0];
    /** The last search's answer: the key and how far into it (GeckoLib's KeyframeLocation). */
    private Keyframe<MathValue> foundFrame;
    private double foundStart;

    /**
     * Plan W0 (docs/plans/2026-10-22-hundred-villagers-fps.md): while the client's fast posing step
     * times a sampled villager it sets this, and each track's "what should play now" check (its state
     * handler) adds its time to {@link #checkNanos}. Render thread only; off, nothing is timed.
     */
    public static boolean timeChecks;
    public static long checkNanos;

    public FastController(T animatable, String name, int transitionTicks, AnimationStateHandler<T> handler) {
        super(animatable, name, transitionTicks, handler);
    }

    @Override
    protected PlayState handleAnimationState(AnimationState<T> state) {
        if (!timeChecks) return super.handleAnimationState(state);
        long start = System.nanoTime();
        try {
            return super.handleAnimationState(state);
        } finally {
            checkNanos += System.nanoTime() - start;
        }
    }

    /** GeckoLib's processor sets this before each {@link #process}; the client's fast processor calls this. */
    public void justStarting(boolean starting) {
        this.isJustStarting = starting;
    }

    /** The easing override for the animatable, as GeckoLib's processor reads it per bone. */
    @Nullable
    public EasingType easingOverride() {
        return this.overrideEasingTypeFunction.apply(this.animatable);
    }

    @Override
    public void process(GeoModel<T> model, AnimationState<T> state, Map<String, GeoBone> bones,
                        Map<String, BoneSnapshot> snapshots, double seekTime, boolean crashWhenCantFindBone) {
        FastPose.Mode mode = FastPose.mode();
        if (mode == FastPose.Mode.GECKO) {
            super.process(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
            return;
        }
        if (mode == FastPose.Mode.SAFE) {
            safeProcess(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
            return;
        }
        try {
            fastProcess(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
        } catch (LinkageError e) {
            // A newer GeckoLib without a field or method this copy uses: GeckoLib's own from next frame.
            FastPose.broken(e);
        }
    }

    // The bones handed to GeckoLib's own process in safe posing: only those any clip keys.
    private Map<String, GeoBone> safeBones;
    private Map<String, GeoBone> safeBonesFrom;
    private Set<String> safeBonesKeyed;

    /**
     * Safe posing (plan step 0c, docs/plans/2026-10-22-hundred-villagers-fps.md), for a GeckoLib
     * whose posing code is not the code {@link #fastProcess} copied: GeckoLib's own {@code process},
     * handed only the bones any loaded clip keys ({@link FastPose#keyedBones}), so it builds a
     * queue for those ~22 bones instead of every bone of the model; and a stopped track's leftover
     * queues are emptied, so the posing step does not poll them every frame. Same pose by
     * construction; only public GeckoLib parts.
     */
    private void safeProcess(GeoModel<T> model, AnimationState<T> state, Map<String, GeoBone> bones,
                             Map<String, BoneSnapshot> snapshots, double seekTime, boolean crashWhenCantFindBone) {
        try {
            super.process(model, state, keyedOnly(bones), snapshots, seekTime, crashWhenCantFindBone);
            if (getAnimationState() == State.STOPPED) {
                Map<String, BoneAnimationQueue> queues = getBoneAnimationQueues();
                if (!queues.isEmpty()) queues.clear();
            }
        } catch (LinkageError e) {
            FastPose.broken(e);
        }
    }

    private Map<String, GeoBone> keyedOnly(Map<String, GeoBone> bones) {
        Set<String> keyed = FastPose.keyedBones();
        if (keyed.isEmpty()) return bones;                    // no clips loaded yet: GeckoLib's full set
        if (bones != this.safeBonesFrom || keyed != this.safeBonesKeyed) {
            Map<String, GeoBone> picked = new Object2ObjectOpenHashMap<>();
            for (Map.Entry<String, GeoBone> entry : bones.entrySet())
                if (keyed.contains(entry.getKey())) picked.put(entry.getKey(), entry.getValue());
            this.safeBones = picked;
            this.safeBonesFrom = bones;
            this.safeBonesKeyed = keyed;
        }
        return this.safeBones;
    }

    /** GeckoLib 4.8.4's {@code AnimationController.process}, line for line except where marked. */
    private void fastProcess(GeoModel<T> model, AnimationState<T> state, Map<String, GeoBone> bones,
                             Map<String, BoneSnapshot> snapshots, double seekTime, boolean crashWhenCantFindBone) {
        double adjustedTick = adjustTick(seekTime);
        this.lastModel = model;

        if (this.animationState == State.TRANSITIONING && adjustedTick >= this.transitionLength) {
            this.shouldResetTick = true;
            this.animationState = State.RUNNING;
            adjustedTick = adjustTick(seekTime);
        }

        PlayState playState = handleAnimationState(state);

        if (playState == PlayState.STOP || (this.currentAnimation == null && this.animationQueue.isEmpty())) {
            this.animationState = State.STOPPED;
            this.justStopped = true;
            // Changed: GeckoLib leaves last frame's emptied queues here for the processor to poll.
            this.boneAnimationQueues.clear();
            return;
        }

        // Changed: GeckoLib's createInitialQueues (a new queue for every bone). Queues are handed
        // out below, only to the bones the clip keys; an unkeyed bone's queue stayed empty anyway.
        this.boneAnimationQueues.clear();

        if (this.justStartedTransition && (this.shouldResetTick || this.justStopped)) {
            this.justStopped = false;
            adjustedTick = adjustTick(seekTime);

            if (this.currentAnimation == null)
                this.animationState = State.TRANSITIONING;
        }
        else if (this.currentAnimation == null) {
            this.shouldResetTick = true;
            this.animationState = State.TRANSITIONING;
            this.justStartedTransition = true;
            this.needsAnimationReload = false;
            adjustedTick = adjustTick(seekTime);
        }
        else if (this.animationState != State.TRANSITIONING) {
            this.animationState = State.RUNNING;
        }

        if (getAnimationState() == State.RUNNING) {
            processCurrentAnimation(adjustedTick, seekTime, bones, crashWhenCantFindBone);
        }
        else if (this.animationState == State.TRANSITIONING) {
            if (this.lastPollTime != seekTime && (adjustedTick == 0 || this.isJustStarting)) {
                this.justStartedTransition = false;
                this.lastPollTime = seekTime;
                this.currentAnimation = this.animationQueue.poll();

                this.executedKeyFrames.clear();

                if (this.currentAnimation == null)
                    return;

                saveSnapshotsForAnimation(this.currentAnimation, snapshots);
            }

            if (this.currentAnimation != null) {
                setAnimTime(0);

                for (BoneAnimation boneAnimation : this.currentAnimation.animation().boneAnimations()) {
                    BoneSnapshot boneSnapshot = this.boneSnapshots.get(boneAnimation.boneName());
                    GeoBone bone = bones.get(boneAnimation.boneName());

                    if (boneSnapshot == null)
                        continue;

                    if (bone == null) {
                        if (crashWhenCantFindBone)
                            throw new RuntimeException("Could not find bone: " + boneAnimation.boneName());

                        continue;
                    }

                    BoneAnimationQueue boneAnimationQueue = queueFor(bone);
                    KeyframeStack<Keyframe<MathValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames();
                    KeyframeStack<Keyframe<MathValue>> positionKeyFrames = boneAnimation.positionKeyFrames();
                    KeyframeStack<Keyframe<MathValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames();

                    if (!rotationKeyFrames.xKeyframes().isEmpty()) {
                        boneAnimationQueue.addNextRotation(null, adjustedTick, this.transitionLength, boneSnapshot, bone.getInitialSnapshot(),
                                pointAtStart(rotationKeyFrames.xKeyframes(), true, Axis.X),
                                pointAtStart(rotationKeyFrames.yKeyframes(), true, Axis.Y),
                                pointAtStart(rotationKeyFrames.zKeyframes(), true, Axis.Z));
                    }

                    if (!positionKeyFrames.xKeyframes().isEmpty()) {
                        boneAnimationQueue.addNextPosition(null, adjustedTick, this.transitionLength, boneSnapshot,
                                pointAtStart(positionKeyFrames.xKeyframes(), false, Axis.X),
                                pointAtStart(positionKeyFrames.yKeyframes(), false, Axis.Y),
                                pointAtStart(positionKeyFrames.zKeyframes(), false, Axis.Z));
                    }

                    if (!scaleKeyFrames.xKeyframes().isEmpty()) {
                        boneAnimationQueue.addNextScale(null, adjustedTick, this.transitionLength, boneSnapshot,
                                pointAtStart(scaleKeyFrames.xKeyframes(), false, Axis.X),
                                pointAtStart(scaleKeyFrames.yKeyframes(), false, Axis.Y),
                                pointAtStart(scaleKeyFrames.zKeyframes(), false, Axis.Z));
                    }
                }
            }
        }
    }

    /** GeckoLib 4.8.4's {@code processCurrentAnimation}; keyframes found by {@link #search}. */
    private void processCurrentAnimation(double adjustedTick, double seekTime, Map<String, GeoBone> bones,
                                         boolean crashWhenCantFindBone) {
        if (adjustedTick >= this.currentAnimation.animation().length()) {
            if (this.currentAnimation.loopType().shouldPlayAgain(this.animatable, this, this.currentAnimation.animation())) {
                if (this.animationState != State.PAUSED) {
                    this.shouldResetTick = true;

                    adjustedTick = adjustTick(seekTime);
                    this.executedKeyFrames.clear();
                }
            }
            else {
                AnimationProcessor.QueuedAnimation nextAnimation = this.animationQueue.peek();

                this.executedKeyFrames.clear();

                if (nextAnimation == null) {
                    this.animationState = State.STOPPED;

                    return;
                }
                else {
                    this.animationState = State.TRANSITIONING;
                    this.shouldResetTick = true;
                    adjustedTick = adjustTick(seekTime);
                    this.currentAnimation = this.animationQueue.poll();
                }
            }
        }

        setAnimTime(adjustedTick / 20d);

        Animation clip = this.currentAnimation.animation();
        BoneAnimation[] boneAnimations = clip.boneAnimations();
        searchFor(clip);

        for (int b = 0; b < boneAnimations.length; b++) {
            BoneAnimation boneAnimation = boneAnimations[b];
            GeoBone bone = bones.get(boneAnimation.boneName());

            if (bone == null) {
                if (crashWhenCantFindBone)
                    throw new RuntimeException("Could not find bone: " + boneAnimation.boneName());

                continue;
            }

            BoneAnimationQueue boneAnimationQueue = queueFor(bone);
            KeyframeStack<Keyframe<MathValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames();
            KeyframeStack<Keyframe<MathValue>> positionKeyFrames = boneAnimation.positionKeyFrames();
            KeyframeStack<Keyframe<MathValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames();
            int slot = b * 9;

            if (!rotationKeyFrames.xKeyframes().isEmpty()) {
                boneAnimationQueue.addRotations(
                        pointAt(rotationKeyFrames.xKeyframes(), adjustedTick, true, Axis.X, slot),
                        pointAt(rotationKeyFrames.yKeyframes(), adjustedTick, true, Axis.Y, slot + 1),
                        pointAt(rotationKeyFrames.zKeyframes(), adjustedTick, true, Axis.Z, slot + 2));
            }

            if (!positionKeyFrames.xKeyframes().isEmpty()) {
                boneAnimationQueue.addPositions(
                        pointAt(positionKeyFrames.xKeyframes(), adjustedTick, false, Axis.X, slot + 3),
                        pointAt(positionKeyFrames.yKeyframes(), adjustedTick, false, Axis.Y, slot + 4),
                        pointAt(positionKeyFrames.zKeyframes(), adjustedTick, false, Axis.Z, slot + 5));
            }

            if (!scaleKeyFrames.xKeyframes().isEmpty()) {
                boneAnimationQueue.addScales(
                        pointAt(scaleKeyFrames.xKeyframes(), adjustedTick, false, Axis.X, slot + 6),
                        pointAt(scaleKeyFrames.yKeyframes(), adjustedTick, false, Axis.Y, slot + 7),
                        pointAt(scaleKeyFrames.zKeyframes(), adjustedTick, false, Axis.Z, slot + 8));
            }
        }

        adjustedTick += this.transitionLength;

        for (SoundKeyframeData keyframeData : clip.keyFrames().sounds()) {
            if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
                if (this.soundKeyframeHandler == null) {
                    LOGGER.warn("Sound Keyframe found for {} -> {}, but no keyframe handler registered",
                            this.animatable.getClass().getSimpleName(), getName());

                    break;
                }

                this.soundKeyframeHandler.handle(new SoundKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
            }
        }

        for (ParticleKeyframeData keyframeData : clip.keyFrames().particles()) {
            if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
                if (this.particleKeyframeHandler == null) {
                    LOGGER.warn("Particle Keyframe found for {} -> {}, but no keyframe handler registered",
                            this.animatable.getClass().getSimpleName(), getName());

                    break;
                }

                this.particleKeyframeHandler.handle(new ParticleKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
            }
        }

        for (CustomInstructionKeyframeData keyframeData : clip.keyFrames().customInstructions()) {
            if (adjustedTick >= keyframeData.getStartTick() && this.executedKeyFrames.add(keyframeData)) {
                if (this.customKeyframeHandler == null) {
                    LOGGER.warn("Custom Instruction Keyframe found for {} -> {}, but no keyframe handler registered",
                            this.animatable.getClass().getSimpleName(), getName());

                    break;
                }

                this.customKeyframeHandler.handle(new CustomInstructionKeyframeEvent<>(this.animatable, adjustedTick, this, keyframeData));
            }
        }

        if (this.transitionLength == 0 && this.shouldResetTick && this.animationState == State.TRANSITIONING)
            this.currentAnimation = this.animationQueue.poll();
    }

    /**
     * The queue for {@code bone} this frame: the one already handed out if the clip keys the
     * bone twice (GeckoLib queues both points too), else this bone's kept queue, emptied.
     */
    private BoneAnimationQueue queueFor(GeoBone bone) {
        String name = bone.getName();
        BoneAnimationQueue queue = this.boneAnimationQueues.get(name);
        if (queue != null)
            return queue;
        queue = this.queuePool.get(name);
        if (queue == null || queue.bone() != bone) {
            // First time, or a resource reload baked new bones.
            queue = new BoneAnimationQueue(bone);
            this.queuePool.put(name, queue);
        } else {
            queue.rotationXQueue().clear();
            queue.rotationYQueue().clear();
            queue.rotationZQueue().clear();
            queue.positionXQueue().clear();
            queue.positionYQueue().clear();
            queue.positionZQueue().clear();
            queue.scaleXQueue().clear();
            queue.scaleYQueue().clear();
            queue.scaleZQueue().clear();
        }
        this.boneAnimationQueues.put(name, queue);
        return queue;
    }

    /** GeckoLib's {@code MathParser.setVariable(ANIM_TIME, () -> value)}, without a new lambda each time. */
    private void setAnimTime(double seconds) {
        this.animTimeNow = seconds;
        MathParser.setVariable(MolangQueries.ANIM_TIME, this.animTimeReader);
    }

    /** GeckoLib's {@code getAnimationPointAtTick(frames, 0, ...)} for a blend's target: a plain walk. */
    private AnimationPoint pointAtStart(List<Keyframe<MathValue>> frames, boolean isRotation, Axis axis) {
        scan(frames, 0);
        return point(isRotation, axis);
    }

    /** GeckoLib's {@code getAnimationPointAtTick}, its key found by {@link #search}. */
    private AnimationPoint pointAt(List<Keyframe<MathValue>> frames, double tick, boolean isRotation, Axis axis, int slot) {
        search(frames, tick, slot);
        return point(isRotation, axis);
    }

    /** The point for {@link #foundFrame} / {@link #foundStart}, as GeckoLib builds it. */
    private AnimationPoint point(boolean isRotation, Axis axis) {
        Keyframe<MathValue> currentFrame = this.foundFrame;
        double startValue = currentFrame.startValue().get();
        double endValue = currentFrame.endValue().get();

        if (isRotation) {
            if (!(currentFrame.startValue() instanceof Constant)) {
                startValue = Math.toRadians(startValue);

                if (axis == Axis.X || axis == Axis.Y)
                    startValue *= -1;
            }

            if (!(currentFrame.endValue() instanceof Constant)) {
                endValue = Math.toRadians(endValue);

                if (axis == Axis.X || axis == Axis.Y)
                    endValue *= -1;
            }
        }

        return new AnimationPoint(currentFrame, this.foundStart, currentFrame.length(), startValue, endValue);
    }

    /** GeckoLib's {@code getCurrentKeyFrameLocation}: walk from the first key. */
    private void scan(List<Keyframe<MathValue>> frames, double ageInTicks) {
        double totalFrameTime = 0;

        for (int i = 0, n = frames.size(); i < n; i++) {
            Keyframe<MathValue> frame = frames.get(i);
            totalFrameTime += frame.length();

            if (totalFrameTime > ageInTicks) {
                this.foundFrame = frame;
                this.foundStart = ageInTicks - (totalFrameTime - frame.length());
                return;
            }
        }

        this.foundFrame = frames.getLast();
        this.foundStart = ageInTicks;
    }

    /** New search memory when the clip changes; index 0 means "walk from the first key". */
    private void searchFor(Animation clip) {
        if (clip == this.searchClip)
            return;
        this.searchClip = clip;
        int slots = clip.boneAnimations().length * 9;
        if (this.searchIndex.length < slots) {
            this.searchIndex = new int[slots];
            this.searchBefore = new double[slots];
            this.searchPassed = new double[slots];
        } else {
            Arrays.fill(this.searchIndex, 0, slots, 0);
        }
    }

    /**
     * The same answer as {@link #scan}, starting where the last search on this channel stopped
     * when every key before that point ended at or before {@code ageInTicks}: the walk would
     * have passed them all. Keys can be stored out of order (a key's length can be negative), so
     * "ended" is the latest end among them, not the last one's. The sums go on from the kept
     * one in the same order, so they are the same doubles.
     */
    private void search(List<Keyframe<MathValue>> frames, double ageInTicks, int slot) {
        int start = this.searchIndex[slot];
        double total = this.searchBefore[slot];
        double passed = this.searchPassed[slot];
        if (start == 0 || !(passed <= ageInTicks)) {
            start = 0;
            total = 0;
            passed = Double.NEGATIVE_INFINITY;
        }

        boolean found = false;
        for (int i = start, n = frames.size(); i < n; i++) {
            Keyframe<MathValue> frame = frames.get(i);
            double before = total;
            total += frame.length();

            if (total > ageInTicks) {
                this.searchIndex[slot] = i;
                this.searchBefore[slot] = before;
                this.searchPassed[slot] = passed;
                this.foundFrame = frame;
                this.foundStart = ageInTicks - (total - frame.length());
                found = true;
                break;
            }
            passed = Math.max(passed, total);
        }

        if (!found) {
            this.searchIndex[slot] = frames.size();
            this.searchBefore[slot] = total;
            this.searchPassed[slot] = passed;
            this.foundFrame = frames.getLast();
            this.foundStart = ageInTicks;
        }

        if (FastPose.checking()) {
            Keyframe<MathValue> fastFrame = this.foundFrame;
            double fastStart = this.foundStart;
            scan(frames, ageInTicks);
            FastPose.lookups++;
            if (fastFrame != this.foundFrame || Double.doubleToLongBits(fastStart) != Double.doubleToLongBits(this.foundStart))
                FastPose.lookupMisses++;
            // GeckoLib's answer is the one used, whatever the check found.
        }
    }

    /** GeckoLib 4.8.4's private {@code saveSnapshotsForAnimation}, unchanged. */
    private void saveSnapshotsForAnimation(AnimationProcessor.QueuedAnimation animation, Map<String, BoneSnapshot> snapshots) {
        for (BoneSnapshot snapshot : snapshots.values()) {
            if (animation.animation().boneAnimations() != null) {
                for (BoneAnimation boneAnimation : animation.animation().boneAnimations()) {
                    if (boneAnimation.boneName().equals(snapshot.getBone().getName())) {
                        this.boneSnapshots.put(boneAnimation.boneName(), BoneSnapshot.copy(snapshot));

                        break;
                    }
                }
            }
        }
    }
}
