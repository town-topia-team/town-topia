package com.alex.test.ui;

import com.alex.test.entity.NpcEntity;
import net.minecraft.world.entity.player.Player;

/** Marker for server menus that should keep their NPC attentive while open. */
public interface NpcUiMenu {
    boolean isForNpc(NpcEntity npc);

    /**
     * A villager's screen stays open however far the villager walks: it closes
     * only when the villager dies, unloads, or ends up in another dimension.
     */
    static boolean villagerStillThere(Player player, NpcEntity villager) {
        return villager.isAlive() && villager.level() == player.level();
    }
}
