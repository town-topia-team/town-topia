package com.alex.test.village.claims;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Village claims round 10: an owned village's land kept safe from the world itself. Each guard is a switch per
 * village, on by default; the owner turns them off on the Land tab's Protection page ({@link VillageClaim#guards}).
 * <ul>
 * <li>{@link Kind#EXPLOSIONS}: creepers, TNT, fireballs, beds in the Nether... break no blocks there, nor item
 *     frames, paintings, leash knots, armour stands, carts and boats. Creatures still get hurt.</li>
 * <li>{@link Kind#FIRE}: fire burns and lights nothing there and goes out within seconds (a fire on netherrack or
 *     soul sand, a fireplace, keeps burning); lava lights nothing; fire next door does not spread in
 *     ({@code mixin/FireBlockClaimMixin}).</li>
 * <li>{@link Kind#ENDERMEN}: endermen near the land neither take nor drop blocks. Other mobs keep their griefing
 *     (raid ravagers still break leaves and crops, as the plan wants).</li>
 * </ul>
 * Claims off, or nobody's village: nothing is guarded. Paused villages keep their guards.
 */
public final class LandGuard {
    private LandGuard() {}

    /** Saved by lower-case name inside the village's {@code claim} tag: never rename one. */
    public enum Kind {
        EXPLOSIONS, FIRE, ENDERMEN;

        public String saveName() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Kind byIndex(int index) {
            Kind[] all = values();
            return index >= 0 && index < all.length ? all[index] : null;
        }
    }

    /** An enderman takes from 2 blocks round itself and puts down 1 round: the land is checked that far out. */
    private static final int ENDERMAN_REACH = 2;
    /** A guarded fire goes out after this many of its ticks (about 2 s each), as one on bare stone does. */
    private static final int TAME_FIRE_AGE = 3;

    /** Whether this spot is on an owned village's land with this guard on; false on a game's side or claims off. */
    public static boolean guards(LevelReader reader, BlockPos pos, Kind kind) {
        if (!(reader instanceof ServerLevel level)) return false;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        if (!registry.claimsEnabled()) return false;
        VillageManager village = ClaimLock.ownedLandAt(registry, level, pos);
        return village != null && village.claim().guards(kind);
    }

    /** The chunk squares of every owned village in this level with this guard on. */
    private static List<int[]> guardedSquares(ServerLevel level, Kind kind) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        if (!registry.claimsEnabled()) return List.of();
        List<int[]> out = new ArrayList<>();
        for (VillageManager village : registry.all()) {
            if (!village.claim().hasOwner() || !village.claim().guards(kind) || village.getLevel() == null
                    || !village.getLevel().dimension().equals(level.dimension())) continue;
            int[] land = village.landChunks();
            if (land != null) out.add(land);
        }
        return out;
    }

    /** Whether this block box (x and z, both ends included) touches any of these chunk squares. */
    private static boolean touches(List<int[]> squares, int minX, int minZ, int maxX, int maxZ) {
        for (int[] land : squares) {
            if ((maxX >> 4) >= land[0] && (minX >> 4) <= land[2] && (maxZ >> 4) >= land[1] && (minZ >> 4) <= land[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean inAny(List<int[]> squares, BlockPos pos) {
        return touches(squares, pos.getX(), pos.getZ(), pos.getX(), pos.getZ());
    }

    // ---------------------------------------------------------------------
    // Explosions
    // ---------------------------------------------------------------------

    /** Last, so blocks another mod added to the explosion are taken out too. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<int[]> squares = guardedSquares(level, Kind.EXPLOSIONS);
        if (squares.isEmpty()) return;
        event.getAffectedBlocks().removeIf(pos -> inAny(squares, pos));
        event.getAffectedEntities().removeIf(entity -> isBuildPart(entity) && inAny(squares, entity.blockPosition()));
    }

    /** Things players build with that an explosion would break: frames (and the tokens in them), stands, carts. */
    private static boolean isBuildPart(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof VehicleEntity;
    }

    // ---------------------------------------------------------------------
    // Fire
    // ---------------------------------------------------------------------

    /** Lava lighting a block on guarded land: nothing happens. */
    @SubscribeEvent
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getNewState().getBlock() instanceof BaseFireBlock
                && guards(event.getLevel(), event.getPos(), Kind.FIRE)) {
            event.setCanceled(true);
        }
    }

    /**
     * A fire on guarded land, instead of its normal tick ({@code FireBlockClaimMixin}): it burns and lights nothing
     * and goes out after a few seconds, as on bare stone. On a block that burns for ever (a fireplace) it stays.
     */
    public static void tameFireTick(FireBlock fire, BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.scheduleTick(pos, fire, 30 + random.nextInt(10));
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return;
        if (!state.canSurvive(level, pos)) {
            level.removeBlock(pos, false);
            return;
        }
        BlockPos below = pos.below();
        if (level.getBlockState(below).isFireSource(level, below, Direction.UP)) return;
        int age = state.getValue(FireBlock.AGE);
        if (age >= TAME_FIRE_AGE) {
            level.removeBlock(pos, false);
            return;
        }
        level.setBlock(pos, state.setValue(FireBlock.AGE, age + 1), 4);
    }

    // ---------------------------------------------------------------------
    // Endermen
    // ---------------------------------------------------------------------

    /** Asked by an enderman every tick it might take or put down a block; only endermen are stopped. */
    @SubscribeEvent
    public static void onGrief(EntityMobGriefingEvent event) {
        if (!(event.getEntity() instanceof EnderMan enderman) || !event.canGrief()
                || !(enderman.level() instanceof ServerLevel level)) return;
        List<int[]> squares = guardedSquares(level, Kind.ENDERMEN);
        if (squares.isEmpty()) return;
        int x = enderman.getBlockX();
        int z = enderman.getBlockZ();
        if (touches(squares, x - ENDERMAN_REACH, z - ENDERMAN_REACH, x + ENDERMAN_REACH, z + ENDERMAN_REACH)) {
            event.setCanGrief(false);
        }
    }
}
