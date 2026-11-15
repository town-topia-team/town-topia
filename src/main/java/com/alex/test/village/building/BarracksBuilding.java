package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Barracks: a walled room for the village's defenders, with optional beds and
 * optional armor stands. Created like every room building -- Barracks token in
 * a frame beside a door. A barracks with no armor stand is still valid; it
 * just has no training posts.
 *
 * <p>Unlike Tavern's note block, an armor stand is an entity rather than a
 * block, so the functional-block scan here queries entities in the room's
 * local AABB instead of block states. {@code functionalBlocks} holds the
 * armor stands' block positions; {@link #getPosts} has the stands themselves.
 * Placing, breaking or loading a stand does not change a block, so
 * {@code VillageManager} calls {@link #refreshPosts} on those entity events.
 */
public final class BarracksBuilding extends Building {

    /** Includes the captain; additional recognised beds remain visible but do not add capacity. */
    public static final int CAPACITY = 10;

    /** One armor stand: a training post for one defender. */
    public record Post(UUID standId, BlockPos standPos, Direction facing) {}

    /** Separate from armor stands: beds must never become drill posts. */
    private final Set<BlockPos> beds = new LinkedHashSet<>();
    /** Both halves of every bed, so drill spots can keep off them. */
    private final Set<BlockPos> bedBlocks = new LinkedHashSet<>();
    private final List<Post> posts = new ArrayList<>();

    public BarracksBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                            Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        beds.clear();
        bedBlocks.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos head = new BlockPos(x, y, z);
                    if (!level.isLoaded(head)) continue;
                    BlockState state = level.getBlockState(head);
                    if (!(state.getBlock() instanceof BedBlock)
                            || state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) continue;
                    BlockPos foot = head.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
                    BlockState footState = level.getBlockState(foot);
                    if (!(footState.getBlock() instanceof BedBlock)
                            || footState.getValue(BlockStateProperties.BED_PART) != BedPart.FOOT) continue;
                    if (isFunctionalBlockAccessibleFromRoom(head)
                            || isFunctionalBlockAccessibleFromRoom(foot)) {
                        beds.add(head);
                        bedBlocks.add(head);
                        bedBlocks.add(foot.immutable());
                    }
                }
            }
        }
        scanArmorStands(level);
        return !functionalBlocks.isEmpty();
    }

    /** Re-reads the armor stands only, after one was placed, broken, loaded or unloaded. */
    public void refreshPosts(ServerLevel level) {
        functionalBlocks.clear();
        scanArmorStands(level);
    }

    private void scanArmorStands(ServerLevel level) {
        posts.clear();
        AABB scanBox = new AABB(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 3, maxZ + 2);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, scanBox)) {
            if (!stand.isAlive() || stand.isMarker()) continue;
            BlockPos pos = stand.blockPosition();
            if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
            functionalBlocks.add(pos);
            posts.add(new Post(stand.getUUID(), pos.immutable(), Direction.fromYRot(stand.getYRot())));
        }
    }

    /** All discovered beds, in stable scan order; beds are optional furniture. */
    public List<BlockPos> getBeds() {
        return List.copyOf(beds);
    }

    @Nullable
    public BlockPos getWalkTargetBesideBed(ServerLevel level, BlockPos head, NavGraph graph, BlockPos from,
                                           java.util.function.Predicate<BlockPos> usable) {
        if (!beds.contains(head) || !level.isLoaded(head)) return null;
        BlockState state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        BlockPos foot = head.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        return getWalkTargetBesideAny(Set.of(head, foot), graph, from, usable);
    }

    /** Every armor stand: the training posts, one defender each. */
    public List<Post> getPosts() {
        return List.copyOf(posts);
    }

    /** Whether {@code pos} is a bed block (head or foot) of this barracks. */
    public boolean isBedBlock(BlockPos pos) {
        return bedBlocks.contains(pos);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
