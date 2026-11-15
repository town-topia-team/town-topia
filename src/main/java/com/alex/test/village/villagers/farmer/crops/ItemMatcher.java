package com.alex.test.village.villagers.farmer.crops;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.List;

/** Matches a planting item by exact id, by tag, or by any of several matchers. */
public sealed interface ItemMatcher {

    boolean matches(Item item);

    record Single(Item item) implements ItemMatcher {
        @Override
        public boolean matches(Item item) {
            return this.item == item;
        }
    }

    record Tagged(TagKey<Item> tag) implements ItemMatcher {
        @Override
        public boolean matches(Item item) {
            return item.builtInRegistryHolder().is(tag);
        }
    }

    record AnyOf(List<ItemMatcher> matchers) implements ItemMatcher {
        @Override
        public boolean matches(Item item) {
            for (ItemMatcher matcher : matchers) if (matcher.matches(item)) return true;
            return false;
        }
    }
}
