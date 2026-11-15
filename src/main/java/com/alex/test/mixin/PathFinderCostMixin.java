package com.alex.test.mixin;

import com.alex.test.performance.ServerCostLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Times Minecraft's path searches for the {@code [ServerCost]} log; one flag check while it is off. */
@Mixin(PathFinder.class)
public abstract class PathFinderCostMixin {

    private static final String FIND_PATH = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;"
            + "Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;";

    @Inject(method = FIND_PATH, at = @At("HEAD"), require = 0)
    private void dynamicvillages$costStart(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float maxRange, int accuracy, float searchDepthMultiplier, CallbackInfoReturnable<Path> cir) {
        if (ServerCostLog.measuring && !mob.level().isClientSide()) ServerCostLog.pathStart();
    }

    @Inject(method = FIND_PATH, at = @At("RETURN"), require = 0)
    private void dynamicvillages$costEnd(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float maxRange, int accuracy, float searchDepthMultiplier, CallbackInfoReturnable<Path> cir) {
        if (ServerCostLog.measuring && !mob.level().isClientSide()) ServerCostLog.pathEnd();
    }
}
