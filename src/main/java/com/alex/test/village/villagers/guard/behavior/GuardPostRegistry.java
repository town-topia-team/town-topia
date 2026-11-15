package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.building.BuildingAccess;
import com.alex.test.village.chat.RegistrationFailure;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent guard-post item-frame state plus transient routing reservations. */
public final class GuardPostRegistry extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_guard_posts";
    private static final String TAG_POSTS = "posts";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_FRAME_ID = "frameId";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";
    private static final String TAG_LAST_VISITED = "lastVisited";
    /** Horizontal radius of the area a post covers. Vertical distance is not a village boundary. */
    public static final int PATROL_RADIUS = 12;
    /** A post this far above the ground around it is on a wall or a tower; see buildPatrolCandidates. */
    private static final int HIGH_POST_HEIGHT = 4;
    /** How far below a high post its guards may still walk (steps and stairs along a wall). */
    private static final int HIGH_POST_DY = 2;
    private static final int HIGH_POST_SAMPLE = 6;
    private static final int RESERVATION_TICKS = 20 * 45;
    private static final int RECONCILE_INTERVAL_TICKS = 20 * 10;

    private static final SavedData.Factory<GuardPostRegistry> FACTORY =
            new SavedData.Factory<>(GuardPostRegistry::new, GuardPostRegistry::load, null);

    private final Map<PostKey, GuardPost> posts = new LinkedHashMap<>();
    private final Map<UUID, Reservation> reservationsByGuard = new HashMap<>();
    private final Map<PostKey, Integer> inboundCountsByPost = new HashMap<>();
    private final Map<UUID, PostKey> activePostByGuard = new HashMap<>();
    private final Map<PostKey, Integer> activeCountsByPost = new HashMap<>();
    private final Map<PostKey, PatrolArea> patrolAreas = new HashMap<>();
    private final Map<PostKey, Boolean> accessState = new HashMap<>();
    /**
     * Posts whose route was found cut, and the game tick it was first seen.
     * Like a building, a post keeps its glint and says nothing until the loss
     * has lasted {@link #ACCESS_LOSS_GRACE_TICKS}: every path rebuild that
     * blipped a route used to send an "unreachable" line and a "reachable
     * again" line for each post nearby. Guards check routes for themselves
     * ({@link #validPosts}), so this only changes the glint and the chat.
     */
    private final Map<PostKey, Long> accessLostSince = new HashMap<>();
    /** Twenty seconds, as for buildings. */
    private static final long ACCESS_LOSS_GRACE_TICKS = 400L;
    private final Set<PendingFrameCheck> pendingFrameChecks = new LinkedHashSet<>();
    private int reconcileCooldown;
    /**
     * {@link #validPosts} worked out once per village per game tick. Every threat
     * scored in a rebalance, and every guard choosing a post in the same tick,
     * asked for the same list, and each walk did two entity box queries per
     * post. Cleared when a frame is registered, removed, reconciled or rechecked
     * so a token placed or broken in the same tick is seen at once.
     */
    @Nullable private UUID validMemoVillage;
    @Nullable private ResourceKey<Level> validMemoDimension;
    private long validMemoTick = Long.MIN_VALUE;
    private List<GuardPost> validMemo = List.of();

    public static GuardPostRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static GuardPostRegistry get(ServerLevel level) {
        return get(level.getServer());
    }

    public void queueFrameCheck(ServerLevel level, BlockPos framePos) {
        pendingFrameChecks.add(new PendingFrameCheck(level.dimension(), framePos.immutable()));
    }

    public void registerFrame(ServerLevel level, ItemFrame frame) {
        clearValidMemo();
        if (!ItemFrameTokenRegistry.isGuardPostToken(frame.getItem()) || !acceptFrameOwner(level, frame)) {
            removeFrame(level, frame.getUUID());
            ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
            if (ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())) {
                VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
                VillageManager target = registry.findVillageAt(level, frame.blockPosition());
                TokenFeedback.announceFailure(level, frame.blockPosition(),
                        ItemFrameTokenRegistry.TokenType.GUARD_POST,
                        target == null ? RegistrationFailure.OUTSIDE_VILLAGE
                                : RegistrationFailure.WRONG_TOWN_HALL, frame.blockPosition());
            }
            return;
        }

        PostKey key = new PostKey(level.dimension(), frame.getUUID());
        GuardPost current = posts.get(key);
        BlockPos pos = frame.blockPosition().immutable();
        VillageManager owner = ownerVillage(level, frame);
        if (owner != null && owner.hasPendingNavRebuild()) {
            pendingFrameChecks.add(new PendingFrameCheck(level.dimension(), pos));
            return;
        }
        if (owner == null || !BuildingAccess.guardPost(owner.getGraph(), owner.getBellPos(), pos)) {
            // Remember the owned frame so a later graph rebuild can activate it
            // without asking the player to remove and replace the token.
            posts.put(key, new GuardPost(key, pos, current != null ? current.lastVisitedAt() : 0L));
            patrolAreas.remove(key);
            setDirty();
            accessState.put(key, false);
            accessLostSince.remove(key);
            ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
            TokenFeedback.announceFailure(level, pos,
                    ItemFrameTokenRegistry.TokenType.GUARD_POST,
                    owner == null || owner.getGraph() == null || owner.getGraph().isEmpty()
                            ? RegistrationFailure.PATH_GRAPH_UNAVAILABLE
                            : RegistrationFailure.GUARD_POST_UNREACHABLE, pos);
            return;
        }
        boolean newlyRegistered = current == null;
        long lastVisited = current != null ? current.lastVisitedAt() : 0L;
        posts.put(key, new GuardPost(key, pos, lastVisited));
        patrolAreas.remove(key);
        ItemFrameTokenRegistry.setRegisteredGlint(frame, true);
        accessState.put(key, true);
        accessLostSince.remove(key);
        setDirty();
        if (newlyRegistered) {
            TokenFeedback.announcePlacement(level, frame.blockPosition(),
                    ItemFrameTokenRegistry.TokenType.GUARD_POST, true, null);
        }
    }

    public void removeFrame(ServerLevel level, UUID frameId) {
        clearValidMemo();
        PostKey key = new PostKey(level.dimension(), frameId);
        if (posts.remove(key) != null) setDirty();
        patrolAreas.remove(key);
        accessState.remove(key);
        removeRuntimeForPost(key);
    }

    public void removeFrame(ServerLevel level, ItemFrame frame) {
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        removeFrame(level, frame.getUUID());
    }

    public boolean isValidPost(ServerLevel level, BlockPos pos) {
        for (GuardPost post : posts.values()) {
            if (!post.key().dimension().equals(level.dimension())) continue;
            if (!post.pos().equals(pos)) continue;
            if (!isFrameStillValid(level, post)) return false;
            ItemFrame frame = frameByIdNear(level, post);
            VillageManager owner = frame != null ? ownerVillage(level, frame) : null;
            return owner != null && BuildingAccess.guardPost(
                    owner.getGraph(), owner.getBellPos(), post.pos());
        }
        return false;
    }

    public int countValidPosts(ServerLevel level, VillageManager village) {
        return validPosts(level, village).size();
    }

    /** Where each of the village's usable posts stands, for the Mayor's overview. */
    public List<BlockPos> validPostPositions(ServerLevel level, VillageManager village) {
        List<BlockPos> positions = new ArrayList<>();
        for (GuardPost post : validPosts(level, village)) positions.add(post.pos());
        return positions;
    }

    @Nullable
    public BlockPos choosePost(
            ServerLevel level,
            VillageManager village,
            NpcEntity guard,
            @Nullable BlockPos recentTarget,
            Set<BlockPos> excludedTargets) {
        long now = level.getGameTime();
        UUID guardId = guard.getNpcId();
        clearExpiredReservations(now);
        releaseReservation(guardId);

        List<GuardPost> candidates = validPosts(level, village);
        candidates.removeIf(post -> excludedTargets.contains(post.pos()));
        if (candidates.isEmpty()) return null;

        GuardPost recent = recentTarget != null ? postAt(level, recentTarget) : null;
        PostKey recentKey = recent != null ? recent.key() : null;

        int bestLoad = Integer.MAX_VALUE;
        boolean hasNonRecentAtBestLoad = false;
        for (GuardPost post : candidates) {
            int load = loadFor(post.key());
            if (load < bestLoad) {
                bestLoad = load;
                hasNonRecentAtBestLoad = !post.key().equals(recentKey);
            } else if (load == bestLoad && !post.key().equals(recentKey)) {
                hasNonRecentAtBestLoad = true;
            }
        }

        GuardPost best = null;
        long bestAge = Long.MIN_VALUE;
        double bestTieBreak = Double.NEGATIVE_INFINITY;
        for (GuardPost post : candidates) {
            PostKey key = post.key();
            int load = loadFor(key);
            if (load != bestLoad) continue;
            if (key.equals(recentKey) && hasNonRecentAtBestLoad) continue;

            long age = Math.max(0L, now - post.lastVisitedAt());
            double distance = Math.sqrt(guard.blockPosition().distSqr(post.pos()));
            double tieBreak = -distance + guard.getRandom().nextDouble() * 2.0D;

            if (best == null || age > bestAge || (age == bestAge && tieBreak > bestTieBreak)) {
                best = post;
                bestAge = age;
                bestTieBreak = tieBreak;
            }
        }

        if (best == null) return null;
        reserve(best.key(), guardId, now);
        return best.pos();
    }

    /**
     * Whether one of the village's posts has nobody but {@code self} walking
     * to it or standing watch at it. An on-duty archer leaves for target
     * practice only when this is false: every post is covered by someone else.
     */
    public boolean hasPostNeedingWatch(ServerLevel level, VillageManager village, UUID self) {
        clearExpiredReservations(level.getGameTime());
        PostKey ownActive = activePostByGuard.get(self);
        Reservation ownReservation = reservationsByGuard.get(self);
        for (GuardPost post : validPosts(level, village)) {
            int others = loadFor(post.key());
            if (post.key().equals(ownActive)) others--;
            if (ownReservation != null && post.key().equals(ownReservation.key())) others--;
            if (others <= 0) return true;
        }
        return false;
    }

    public boolean shouldRelocateFrom(
            ServerLevel level,
            VillageManager village,
            NpcEntity guard,
            BlockPos currentTarget) {
        long now = level.getGameTime();
        clearExpiredReservations(now);

        GuardPost current = postAt(level, currentTarget);
        if (current == null || !village.contains(current.pos()) || !level.hasChunkAt(current.pos())) {
            return false;
        }

        int currentLoad = loadFor(current.key());
        int bestOtherLoad = Integer.MAX_VALUE;
        for (GuardPost post : validPosts(level, village)) {
            if (post.key().equals(current.key())) continue;
            bestOtherLoad = Math.min(bestOtherLoad, loadFor(post.key()));
        }

        return bestOtherLoad != Integer.MAX_VALUE && currentLoad > bestOtherLoad + 1;
    }

    public void markVisited(ServerLevel level, BlockPos pos, UUID guardId) {
        GuardPost post = postAt(level, pos);
        if (post == null) return;
        posts.put(post.key(), new GuardPost(post.key(), post.pos(), level.getGameTime()));
        setDirty();
        // The visit time feeds choosePost's "least recently visited" pick.
        clearValidMemo();
    }

    public void releaseReservation(UUID guardId) {
        Reservation reservation = reservationsByGuard.remove(guardId);
        if (reservation == null) return;
        decrement(inboundCountsByPost, reservation.key());
    }

    public void markActive(ServerLevel level, BlockPos pos, UUID guardId) {
        GuardPost post = postAt(level, pos);
        if (post == null) return;
        releaseReservation(guardId);
        releaseActive(guardId);
        activePostByGuard.put(guardId, post.key());
        increment(activeCountsByPost, post.key());
    }

    public void releaseActive(UUID guardId) {
        PostKey key = activePostByGuard.remove(guardId);
        if (key == null) return;
        decrement(activeCountsByPost, key);
    }

    public List<BlockPos> patrolCandidates(ServerLevel level, VillageManager village, BlockPos pos) {
        GuardPost post = postAt(level, pos);
        NavGraph graph = village.getGraph();
        if (post == null || graph == null) return List.of();
        ItemFrame frame = frameByIdNear(level, post);
        if (frame == null || !isFrameStillValid(level, post)
                || !VillageTokenBinding.matches(frame.getItem(), village)
                || !BuildingAccess.guardPost(graph, village.getBellPos(), post.pos())) return List.of();

        PatrolArea cached = patrolAreas.get(post.key());
        // Good while nothing changed within the patrol area; an edit at the
        // far end of the village used to throw every post's area away.
        if (cached != null
                && cached.center().equals(post.pos())
                && !graph.changedSince(cached.graphVersion(), post.pos(), PATROL_RADIUS, PATROL_CACHE_DY)) {
            return cached.candidates();
        }

        List<BlockPos> candidates = buildPatrolCandidates(graph, village.getBellPos(), post.pos(),
                isHighPost(level, post.pos()));
        PatrolArea area = new PatrolArea(graph.version(), post.pos(), List.copyOf(candidates));
        patrolAreas.put(post.key(), area);
        return area.candidates();
    }

    public double nearestPostDistanceSqr(ServerLevel level, VillageManager village, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (GuardPost post : validPosts(level, village)) {
            best = Math.min(best, post.pos().distSqr(pos));
        }
        return best;
    }

    public void tick(ServerLevel level) {
        processPendingFrameChecks(level);
        if (--reconcileCooldown <= 0) {
            reconcileCooldown = RECONCILE_INTERVAL_TICKS;
            reconcileLoadedPosts(level);
        }
    }

    private void processPendingFrameChecks(ServerLevel level) {
        if (pendingFrameChecks.isEmpty()) return;
        // Take this dimension's checks out first: registerFrame can re-queue a frame
        // (village map still rebuilding), and adding to the set mid-loop crashes.
        List<PendingFrameCheck> batch = new ArrayList<>();
        Iterator<PendingFrameCheck> iterator = pendingFrameChecks.iterator();
        while (iterator.hasNext()) {
            PendingFrameCheck check = iterator.next();
            if (!check.dimension().equals(level.dimension())) continue;
            iterator.remove();
            batch.add(check);
        }

        for (PendingFrameCheck check : batch) {
            // Every right-clicked frame lands here, so only ever touch that exact frame.
            ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(level, check.pos());
            if (frame == null) continue;
            if (ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())) {
                registerFrame(level, frame);
            } else {
                // Forget a post this frame used to hold, but leave the glint alone: it
                // belongs to whatever token the frame holds now. Clearing it here wiped
                // the glint off every Storage/house token a player right-clicked.
                removeFrame(level, frame.getUUID());
            }
        }
    }

    private void reserve(PostKey key, UUID guardId, long now) {
        releaseReservation(guardId);
        reservationsByGuard.put(guardId, new Reservation(key, now + RESERVATION_TICKS));
        increment(inboundCountsByPost, key);
    }

    private void clearExpiredReservations(long now) {
        Iterator<Map.Entry<UUID, Reservation>> iterator = reservationsByGuard.entrySet().iterator();
        while (iterator.hasNext()) {
            Reservation reservation = iterator.next().getValue();
            if (reservation.expiresAt() > now) continue;
            iterator.remove();
            decrement(inboundCountsByPost, reservation.key());
        }
    }

    private List<GuardPost> validPosts(ServerLevel level, VillageManager village) {
        // Same village, same tick: hand out a copy of the list worked out before
        // (callers such as choosePost remove entries from what they get).
        if (validMemoTick == level.getGameTime()
                && village.getVillageId().equals(validMemoVillage)
                && level.dimension().equals(validMemoDimension)) {
            return new ArrayList<>(validMemo);
        }
        List<GuardPost> candidates = new ArrayList<>();
        for (GuardPost post : posts.values()) {
            if (!post.key().dimension().equals(level.dimension())) continue;
            if (!village.contains(post.pos())) continue;
            if (!level.hasChunkAt(post.pos())) continue;
            ItemFrame frame = frameByIdNear(level, post);
            if (frame == null || !isFrameStillValid(level, post)
                    || !VillageTokenBinding.matches(frame.getItem(), village)) continue;
            // A post is useful only when its nearby patrol area contains a
            // routable position connected to the Town Hall.
            if (!BuildingAccess.guardPost(village.getGraph(), village.getBellPos(), post.pos())
                    || patrolCandidates(level, village, post.pos()).isEmpty()) continue;
            candidates.add(post);
        }
        validMemoVillage = village.getVillageId();
        validMemoDimension = level.dimension();
        validMemoTick = level.getGameTime();
        validMemo = List.copyOf(candidates);
        return candidates;
    }

    /** Forget the per-tick valid post list; the next validPosts call works it out again. */
    private void clearValidMemo() {
        validMemoTick = Long.MIN_VALUE;
    }

    private int loadFor(PostKey key) {
        return activeCountsByPost.getOrDefault(key, 0)
                + inboundCountsByPost.getOrDefault(key, 0);
    }

    private void reconcileLoadedPosts(ServerLevel level) {
        boolean changed = false;
        Iterator<Map.Entry<PostKey, GuardPost>> iterator = posts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PostKey, GuardPost> entry = iterator.next();
            GuardPost post = entry.getValue();
            if (!post.key().dimension().equals(level.dimension())) continue;
            if (!level.hasChunkAt(post.pos())) continue;
            ItemFrame frame = frameByIdNear(level, post);
            if (frame == null || !ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())
                    || !acceptFrameOwner(level, frame)) {
                iterator.remove();
                patrolAreas.remove(post.key());
                accessState.remove(post.key());
                removeRuntimeForPost(post.key());
                clearValidMemo();
                if (frame != null) ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
                changed = true;
                continue;
            }
            if (!frame.blockPosition().equals(post.pos())) {
                entry.setValue(new GuardPost(
                        post.key(), frame.blockPosition().immutable(), post.lastVisitedAt()));
                patrolAreas.remove(post.key());
                changed = true;
            }
            VillageManager owner = ownerVillage(level, frame);
            boolean reachable = owner != null && BuildingAccess.guardPost(
                    owner.getGraph(), owner.getBellPos(), post.pos());
            settleAccess(level, post, frame, reachable, owner);
        }
        if (changed) {
            setDirty();
            clearValidMemo();
        }
    }

    /**
     * Shows a post's route on its token and in chat. Found again, it counts
     * at once; lost, only once it has stayed lost for
     * {@link #ACCESS_LOSS_GRACE_TICKS}, looked at again by the next reconcile
     * or graph rebuild. Not while a graph update is pending: the route may be
     * back in it. The first look after loading says nothing either way.
     */
    private void settleAccess(ServerLevel level, GuardPost post, ItemFrame frame, boolean reachable,
                              @Nullable VillageManager owner) {
        PostKey key = post.key();
        Boolean shown = accessState.get(key);
        boolean graphUnavailable = owner == null || owner.getGraph() == null || owner.getGraph().isEmpty();
        if (reachable) {
            accessLostSince.remove(key);
            accessState.put(key, true);
            ItemFrameTokenRegistry.setRegisteredGlint(frame, true);
            if (Boolean.FALSE.equals(shown)) {
                TokenFeedback.announceAccessChange(level, post.pos(),
                        ItemFrameTokenRegistry.TokenType.GUARD_POST, true, post.pos(), false);
            }
            return;
        }
        if (!Boolean.TRUE.equals(shown)) {
            accessState.put(key, false);
            ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
            return;
        }
        long now = level.getGameTime();
        Long since = accessLostSince.putIfAbsent(key, now);
        if (since == null || now - since < ACCESS_LOSS_GRACE_TICKS) return;
        if (owner != null && owner.hasPendingNavRebuild()) return;
        accessLostSince.remove(key);
        accessState.put(key, false);
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announceAccessChange(level, post.pos(),
                ItemFrameTokenRegistry.TokenType.GUARD_POST, false, post.pos(), graphUnavailable);
    }

    private boolean isFrameStillValid(ServerLevel level, GuardPost post) {
        ItemFrame frame = frameByIdNear(level, post);
        boolean valid = frame != null && ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())
                && acceptFrameOwner(level, frame);
        return valid;
    }

    /** Refresh posts after a graph rebuild, including routes broken away from the frame. */
    public void recheckAccess(ServerLevel level, VillageManager village) {
        clearValidMemo();
        for (GuardPost post : posts.values()) {
            if (!post.key().dimension().equals(level.dimension())
                    || !village.contains(post.pos())) continue;
            // The access flood is coloured over a few ticks after an update,
            // and this runs when it is done. A patrol area worked out in
            // between was read off the flood half-coloured; drop it.
            patrolAreas.remove(post.key());
            if (!level.hasChunkAt(post.pos())) continue;
            ItemFrame frame = frameByIdNear(level, post);
            if (frame == null || !isFrameStillValid(level, post)
                    || !VillageTokenBinding.matches(frame.getItem(), village)) continue;
            boolean reachable = BuildingAccess.guardPost(village.getGraph(), village.getBellPos(), post.pos());
            settleAccess(level, post, frame, reachable, village);
        }
    }

    @Nullable
    private VillageManager ownerVillage(ServerLevel level, ItemFrame frame) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageTokenBinding binding = VillageTokenBinding.read(frame.getItem());
        VillageManager owner = binding != null
                ? registry.get(binding.townHallId()) : registry.findVillageAt(level, frame.blockPosition());
        if (owner == null && VillageManager.INSTANCE.getLevel() == level
                && VillageManager.INSTANCE.contains(frame.blockPosition())) return VillageManager.INSTANCE;
        return owner;
    }

    private boolean acceptFrameOwner(ServerLevel level, ItemFrame frame) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageTokenBinding binding = VillageTokenBinding.read(frame.getItem());
        VillageManager village = binding != null
                ? registry.get(binding.townHallId()) : registry.findVillageAt(level, frame.blockPosition());
        if (village == null && VillageManager.INSTANCE.getLevel() == level
                && VillageManager.INSTANCE.contains(frame.blockPosition())) {
            village = VillageManager.INSTANCE;
        }
        // Keep ownership registered while the Town Hall is being repaired; village
        // ticking already pauses its guards, and other villages fail the binding check.
        return village != null && VillageTokenBinding.bindFrame(frame, village);
    }

    @Nullable
    private GuardPost postAt(ServerLevel level, BlockPos pos) {
        for (GuardPost post : posts.values()) {
            if (post.key().dimension().equals(level.dimension()) && post.pos().equals(pos)) {
                return post;
            }
        }
        return null;
    }

    @Nullable
    private ItemFrame frameByIdNear(ServerLevel level, GuardPost post) {
        AABB box = new AABB(post.pos()).inflate(1.0D);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (frame.getUUID().equals(post.key().frameId())) return frame;
        }
        return null;
    }

    /**
     * Where a guard at this post walks: anywhere within {@link #PATROL_RADIUS}
     * blocks of it. A post set high up ({@code high}: on a wall or a tower)
     * keeps to its own level, give or take {@value #HIGH_POST_DY} blocks, and
     * may use the floor right beside the token, so a post on a wall walks the
     * wall and one on a small tower top stays on it. It used to send its guards
     * down to the ground around it, preferring spots 7 to 8 blocks out.
     */
    private static List<BlockPos> buildPatrolCandidates(NavGraph graph, BlockPos origin,
                                                        BlockPos center, boolean high) {
        List<BlockPos> candidates = new ArrayList<>();
        int radiusSq = PATROL_RADIUS * PATROL_RADIUS;
        for (NavNode node : graph.nodesInBox(center, PATROL_RADIUS, high ? HIGH_POST_DY + 1 : Integer.MAX_VALUE)) {
            BlockPos pos = node.feetPos();
            int dx = pos.getX() - center.getX();
            int dz = pos.getZ() - center.getZ();
            int horizontalDistSq = dx * dx + dz * dz;
            if (horizontalDistSq > radiusSq || (!high && horizontalDistSq < 4)) continue;
            if (high && (pos.getY() - center.getY() > 1 || center.getY() - pos.getY() > HIGH_POST_DY)) continue;
            // Up on a wall or tower the ladder up is within reach of the post,
            // and a guard sent to a rung of it stood clinging to the ladder.
            if (high && node.climb) continue;
            if (!BuildingAccess.isAccessibleNode(graph, origin, node)) continue;
            candidates.add(pos);
        }
        return candidates;
    }

    /**
     * Whether a post stands at least {@value #HIGH_POST_HEIGHT} blocks above
     * the ground around it: the middle of eight samples taken
     * {@value #HIGH_POST_SAMPLE} blocks out, ignoring any as tall as the post
     * (the wall or tower it is on). With nothing left to measure it is not high.
     */
    private static boolean isHighPost(ServerLevel level, BlockPos post) {
        List<Integer> grounds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4.0D * i;
            int x = (int) Math.floor(post.getX() + 0.5D + Math.cos(angle) * HIGH_POST_SAMPLE);
            int z = (int) Math.floor(post.getZ() + 0.5D + Math.sin(angle) * HIGH_POST_SAMPLE);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    x, z);
            if (ground >= post.getY() - 1) continue;
            grounds.add(ground);
        }
        if (grounds.isEmpty()) return false;
        grounds.sort(Integer::compare);
        return post.getY() - grounds.get(grounds.size() / 2) >= HIGH_POST_HEIGHT;
    }

    private void removeRuntimeForPost(PostKey key) {
        accessLostSince.remove(key);
        inboundCountsByPost.remove(key);
        activeCountsByPost.remove(key);
        reservationsByGuard.entrySet().removeIf(entry -> entry.getValue().key().equals(key));
        activePostByGuard.entrySet().removeIf(entry -> entry.getValue().equals(key));
    }

    private static void increment(Map<PostKey, Integer> counts, PostKey key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static void decrement(Map<PostKey, Integer> counts, PostKey key) {
        Integer count = counts.get(key);
        if (count == null) return;
        if (count <= 1) counts.remove(key);
        else counts.put(key, count - 1);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (GuardPost post : posts.values()) {
            CompoundTag entry = new CompoundTag();
            BlockPos pos = post.pos();
            entry.putString(TAG_DIMENSION, post.key().dimension().location().toString());
            entry.putUUID(TAG_FRAME_ID, post.key().frameId());
            entry.putInt(TAG_X, pos.getX());
            entry.putInt(TAG_Y, pos.getY());
            entry.putInt(TAG_Z, pos.getZ());
            entry.putLong(TAG_LAST_VISITED, post.lastVisitedAt());
            list.add(entry);
        }
        tag.put(TAG_POSTS, list);
        return tag;
    }

    private static GuardPostRegistry load(
            CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        GuardPostRegistry registry = new GuardPostRegistry();
        ListTag list = tag.getList(TAG_POSTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString(TAG_DIMENSION));
            if (dimensionId == null || !entry.hasUUID(TAG_FRAME_ID)) continue;
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            BlockPos pos = new BlockPos(entry.getInt(TAG_X), entry.getInt(TAG_Y), entry.getInt(TAG_Z));
            PostKey key = new PostKey(dimension, entry.getUUID(TAG_FRAME_ID));
            registry.posts.put(key, new GuardPost(key, pos, entry.getLong(TAG_LAST_VISITED)));
        }
        return registry;
    }

    private record PostKey(ResourceKey<Level> dimension, UUID frameId) {}
    private record GuardPost(PostKey key, BlockPos pos, long lastVisitedAt) {}
    private record Reservation(PostKey key, long expiresAt) {}
    private record PatrolArea(long graphVersion, BlockPos center, List<BlockPos> candidates) {}

    /**
     * How far up and down a patrol area's cells are watched for changes. A
     * low post's candidates may sit on any floor of its column, so this
     * covers the whole height a village spans.
     */
    private static final int PATROL_CACHE_DY = 128;
    private record PendingFrameCheck(ResourceKey<Level> dimension, BlockPos pos) {}

}
