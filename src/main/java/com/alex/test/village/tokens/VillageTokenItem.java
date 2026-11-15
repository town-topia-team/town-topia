package com.alex.test.village.tokens;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Makes village-specific stacks distinguishable in inventories and supply chests.
 *
 * <p>A token lying on the ground cannot be lost: fire, lava, explosions and
 * cactus do not hurt it, it never despawns, and one that falls out of the
 * world is put back on the ground. A lost Town Hall token would otherwise
 * leave its village paused for good.
 */
public final class VillageTokenItem extends Item {
    /** The age at which a dropped item stops ageing, so it never despawns. */
    private static final int NEVER_DESPAWNS = -32768;
    /** How far below the world a token falls before it is brought back; the game deletes items at 64. */
    private static final int VOID_RESCUE_DEPTH = 32;

    public VillageTokenItem(Properties properties) {
        super(properties.fireResistant());
    }

    @Override
    public boolean canBeHurtBy(ItemStack stack, DamageSource source) {
        return false;
    }

    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getAge() != NEVER_DESPAWNS) entity.setUnlimitedLifetime();
        if (entity.getY() < level.getMinBuildHeight() - VOID_RESCUE_DEPTH) rescueFromVoid(level, entity);
        return false;
    }

    /**
     * Back onto the highest block above where it fell, else beside the nearest
     * player. With neither, it waits where it is, held up, until a player comes.
     */
    private static void rescueFromVoid(ServerLevel level, ItemEntity entity) {
        BlockPos column = BlockPos.containing(entity.getX(), level.getMinBuildHeight(), entity.getZ());
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
        Vec3 to = null;
        if (top.getY() > level.getMinBuildHeight()) {
            to = Vec3.atBottomCenterOf(top);
        } else {
            Player player = level.getNearestPlayer(entity, -1.0D);
            if (player != null) to = player.position();
        }
        entity.setDeltaMovement(Vec3.ZERO);
        if (to == null) {
            entity.setNoGravity(true);
            return;
        }
        entity.setNoGravity(false);
        entity.teleportTo(to.x, to.y, to.z);
        DynamicVillages.LOGGER.info("[Token] {} fell out of the world; brought back up at {}.",
                entity.getItem().getHoverName().getString(), BlockPos.containing(to));
    }
}
