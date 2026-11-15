package com.alex.test.client;

import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Loads every thought-bubble texture at resource-reload time.
 *
 * <p>Each icon is its own texture rather than a sprite in an atlas, so the
 * first time one is drawn Minecraft has to go and fetch it — and until it
 * arrives the quad is drawn plain white. That is invisible for a single bubble
 * but very visible when a crowd starts talking at once: photographing
 * {@code /villager thoughts lineup} caught two blank white bubbles still
 * standing a second and a half after the villagers spawned.
 *
 * <p>Sixty-six small textures is about a megabyte, loaded once per resource
 * reload, which is the right place to pay for it: a bubble is up for two
 * seconds and has to be legible for all of them.
 */
public final class ThoughtIconPreloader implements ResourceManagerReloadListener {

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        var textures = Minecraft.getInstance().getTextureManager();
        for (ThoughtIcon icon : ThoughtIcon.values()) {
            textures.getTexture(icon.texture());
        }
        textures.getTexture(ThoughtBubbleRenderer.bubbleTexture(false));
        textures.getTexture(ThoughtBubbleRenderer.bubbleTexture(true));
    }
}
