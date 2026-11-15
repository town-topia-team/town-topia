package com.alex.test.village.villagers.farmer.crops;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** Matches a crop/fruit/soil block by exact id, by tag, or by any of several matchers. */
public sealed interface BlockMatcher {

    boolean matches(Block block);

    record Single(Block block) implements BlockMatcher {
        @Override
        public boolean matches(Block block) {
            return this.block == block;
        }
    }

    record Tagged(TagKey<Block> tag) implements BlockMatcher {
        @Override
        public boolean matches(Block block) {
            return block.builtInRegistryHolder().is(tag);
        }
    }

    record AnyOf(List<BlockMatcher> matchers) implements BlockMatcher {
        @Override
        public boolean matches(Block block) {
            for (BlockMatcher matcher : matchers) if (matcher.matches(block)) return true;
            return false;
        }
    }
}
