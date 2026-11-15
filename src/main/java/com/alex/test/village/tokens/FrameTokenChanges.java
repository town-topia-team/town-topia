package com.alex.test.village.tokens;

import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

/**
 * Notices a token leaving its item frame while the frame stays on the wall.
 *
 * <p>Breaking the frame and right-clicking it already have events. Punching
 * the token out, shooting it out or a command emptying the frame had none, so
 * only the slow rolling re-check (one building every five seconds) found the
 * building gone, minutes later in a big village. Called from the item frame's
 * own {@code setItem}, which every change of its item passes through, so it
 * costs nothing until a frame's item actually changes.
 */
public final class FrameTokenChanges {

    private FrameTokenChanges() {}

    /** Just before {@code frame}'s item becomes {@code next}. */
    public static void beforeItemChange(ItemFrame frame, ItemStack next) {
        if (!(frame.level() instanceof ServerLevel sl) || sl.getServer() == null) return;
        // A frame read from disk starts empty, so loading a chunk never gets here.
        ItemStack current = frame.getItem();
        if (current.isEmpty() || !ItemFrameTokenRegistry.isToken(current)) return;
        if (ItemStack.isSameItemSameComponents(current, next)) return;
        // The same check a right-click on the frame queues, run next tick once
        // the frame holds its new item.
        if (ItemFrameTokenRegistry.isGuardPostToken(current)) {
            GuardPostRegistry.get(sl).queueFrameCheck(sl, frame.blockPosition());
        } else {
            VillageManagerRegistry.get(sl.getServer()).onFrameTokenLeaving(sl, frame.blockPosition());
        }
    }
}
