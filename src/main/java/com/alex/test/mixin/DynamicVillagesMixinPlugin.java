package com.alex.test.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Leaves out mixins that cannot apply next to another mod. Mixin refuses to inject into a method
 * a different mixin has overwritten, even with {@code require = 0}, and that stops the server:
 * Moonrise (the beta server's chunk system) replaces {@code ServerChunkCache.getChunk}, so the
 * chunk-stall timer is skipped there and the {@code [FarAwake]} stall count stays 0.
 */
public final class DynamicVillagesMixinPlugin implements IMixinConfigPlugin {

    private static boolean loaded(String modId) {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".ServerChunkCacheStallMixin")) return !loaded("moonrise");
        return true;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
