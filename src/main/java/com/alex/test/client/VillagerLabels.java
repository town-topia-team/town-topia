package com.alex.test.client;

import com.alex.test.ClientConfig;
import com.alex.test.entity.NpcEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import javax.annotation.Nullable;

/**
 * Which villager names are drawn over heads (read by {@link NpcGeoRenderer#shouldShowName}).
 *
 * <p>Normally only the villager the player is looking at shows its name: the look
 * ray reaches {@link #LOOK_REACH} blocks and stops at the first solid block, so a
 * name never shows through a wall. Vanilla's own crosshair target (3 blocks) counts too.
 *
 * <p>{@code /villagerstatus on} is the testing view: every villager within
 * {@link DrawBatching#nameDistance} blocks shows "Bram · work" as the old labels did.
 * Saved in the client config, so it stays on until {@code /villagerstatus off}. It
 * needs ops or cheats, both to set and to draw, so it cannot show on a server where
 * the player is not an op.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VillagerLabels {

    /** How far, in blocks, a player can read the name of the villager they look at. */
    public static final double LOOK_REACH = 16;

    /** The villager the player looked at on the last client tick, or null. */
    @Nullable
    private static NpcEntity lookedAt;

    private VillagerLabels() {}

    /** The villager under the player's gaze within {@link #LOOK_REACH}, as of the last tick. */
    @Nullable
    public static NpcEntity lookedAt() {
        return lookedAt;
    }

    /** Whether the {@code /villagerstatus} testing view is on and allowed for this player. */
    public static boolean statusShown() {
        Minecraft mc = Minecraft.getInstance();
        return ClientConfig.showWorkStatus() && mc.player != null && mc.player.hasPermissions(2);
    }

    /** "Bram · work", or whichever half the villager has. */
    public static Component withStatus(NpcEntity npc, Component name) {
        String status = npc.workStatus();
        if (status.isEmpty()) return name;
        if (!npc.hasCustomName()) return Component.literal(status);
        return name.copy().append(" · " + status);
    }

    /** GeckoLib's other name rules, for a name we have chosen to show: F1, invisibility, teams, riders. */
    public static boolean nameVisibleToPlayer(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return false;
        boolean visibleToClient = !entity.isInvisibleTo(minecraft.player);
        Team entityTeam = entity.getTeam();
        if (entityTeam == null)
            return Minecraft.renderNames() && entity != minecraft.getCameraEntity() && visibleToClient && !entity.isVehicle();
        Team playerTeam = minecraft.player.getTeam();
        return switch (entityTeam.getNameTagVisibility()) {
            case ALWAYS -> visibleToClient;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> playerTeam == null ? visibleToClient
                    : entityTeam.isAlliedTo(playerTeam) && (entityTeam.canSeeFriendlyInvisibles() || visibleToClient);
            case HIDE_FOR_OWN_TEAM -> playerTeam == null ? visibleToClient
                    : !entityTeam.isAlliedTo(playerTeam) && visibleToClient;
        };
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        lookedAt = findLookedAt();
    }

    @Nullable
    private static NpcEntity findLookedAt() {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) return null;
        Vec3 eye = camera.getEyePosition(1f);
        Vec3 look = camera.getViewVector(1f);
        Vec3 end = eye.add(look.scale(LOOK_REACH));
        AABB sweep = camera.getBoundingBox().expandTowards(look.scale(LOOK_REACH)).inflate(1);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end, sweep,
                e -> e instanceof NpcEntity && !e.isSpectator() && e.isPickable(), LOOK_REACH * LOOK_REACH);
        if (hit == null) return null;
        // Blocks you can walk through (grass, flowers) do not hide a name; walls and glass do.
        HitResult block = mc.level.clip(new ClipContext(eye, hit.getLocation(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, camera));
        if (block.getType() != HitResult.Type.MISS
                && block.getLocation().distanceToSqr(eye) < hit.getLocation().distanceToSqr(eye)) return null;
        return (NpcEntity) hit.getEntity();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("villagerstatus")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> {
                    ClientConfig.setShowWorkStatus(true);
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ClientConfig.setShowWorkStatus(false);
                    return report(ctx.getSource());
                }));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(ClientConfig.showWorkStatus()
                ? "Work status on: villagers within " + (int) DrawBatching.nameDistance
                        + " blocks show their name and what they are doing. Stays on until /villagerstatus off."
                : "Work status off: a villager shows its name only when you look at it."), false);
        return 1;
    }
}
