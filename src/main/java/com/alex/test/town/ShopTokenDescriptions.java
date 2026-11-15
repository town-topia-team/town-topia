package com.alex.test.town;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;

/** Short descriptions shown when hovering over Mayor shop tokens. Edit the text here. */
public final class ShopTokenDescriptions {

    private ShopTokenDescriptions() {}

    /**
     * The hover text for a shop token, in the player's language (empty for
     * anything else). Each one is also the token's own "item.<id>.desc" line.
     */
    public static String forItem(String itemId) {
        return switch (itemId) {
            // Building tokens
            case DynamicVillages.MODID + ":town_hall" -> Tr.s("item.dynamicvillages.town_hall.desc",
                    "Registers the village's central Town Hall.");
            case DynamicVillages.MODID + ":2_bed_house" -> Tr.s("item.dynamicvillages.2_bed_house.desc",
                    "A home for up to two villagers.");
            case DynamicVillages.MODID + ":4_bed_house" -> Tr.s("item.dynamicvillages.4_bed_house.desc",
                    "A home for up to four villagers.");
            case DynamicVillages.MODID + ":6_bed_house" -> Tr.s("item.dynamicvillages.6_bed_house.desc",
                    "A home for up to six villagers.");
            case DynamicVillages.MODID + ":storage_room" -> Tr.s("item.dynamicvillages.storage_room.desc",
                    "Shared storage for village supplies.");
            case DynamicVillages.MODID + ":guard_post" -> Tr.s("item.dynamicvillages.guard_post.desc",
                    "Marks a patrol position for guards.");
            case DynamicVillages.MODID + ":kitchen" -> Tr.s("item.dynamicvillages.kitchen.desc",
                    "A workspace for village cooks.");
            case DynamicVillages.MODID + ":butchery" -> Tr.s("item.dynamicvillages.butchery.desc",
                    "Hang beside the door of a walled room. "
                    + "Your butcher leads animals here from the ranch. "
                    + "Needs a stripped log as a chopping block.");
            case DynamicVillages.MODID + ":ranch" -> Tr.s("item.dynamicvillages.ranch.desc",
                    "A fenced pen for village animals.");
            case DynamicVillages.MODID + ":mine" -> Tr.s("item.dynamicvillages.mine.desc",
                    "A stone shaft for village miners.");
            case DynamicVillages.MODID + ":forge" -> Tr.s("item.dynamicvillages.forge.desc",
                    "A workshop for village blacksmiths.");
            case DynamicVillages.MODID + ":stall" -> Tr.s("item.dynamicvillages.stall.desc",
                    "A counter for trading village goods.");
            case DynamicVillages.MODID + ":barracks" -> Tr.s("item.dynamicvillages.barracks.desc",
                    "Drill space for village defenders.");
            case DynamicVillages.MODID + ":archery_range" -> Tr.s("item.dynamicvillages.archery_range.desc",
                    "Where archers practise and make arrows. Fence in a yard "
                    + "with a gate, hang this in a frame next to the gate, and put target blocks inside.");
            case DynamicVillages.MODID + ":watchtower" -> Tr.s("item.dynamicvillages.watchtower.desc",
                    "A lookout spot for an archer. Build a floor 5+ blocks high "
                    + "with a ladder or stairs, and hang this in a frame on that floor, near the middle.");
            case DynamicVillages.MODID + ":school" -> Tr.s("item.dynamicvillages.school.desc",
                    "A classroom for village lessons.");
            case DynamicVillages.MODID + ":tavern" -> Tr.s("item.dynamicvillages.tavern.desc",
                    "Where the bard plays and visiting merchants and nomads "
                    + "spend the evening. Beds inside become guest beds, so more visitors stay the night.");
            case DynamicVillages.MODID + ":library" -> Tr.s("item.dynamicvillages.library.desc",
                    "A workspace for enchanting books.");
            case DynamicVillages.MODID + ":mage_tower" -> Tr.s("item.dynamicvillages.mage_tower.desc",
                    "Where mages are hired, study and sleep. Needs an enchanting "
                    + "table or lectern, bookshelves and an amethyst crystal. More bookshelves, faster study; "
                    + "beds inside are for mages only.");

            // Profession tokens
            case DynamicVillages.MODID + ":lumberjack_token" -> Tr.s("item.dynamicvillages.lumberjack_token.desc",
                    "Chops and gathers wood for the village.");
            case DynamicVillages.MODID + ":farmer_token" -> Tr.s("item.dynamicvillages.farmer_token.desc",
                    "Plants and harvests village crops.");
            case DynamicVillages.MODID + ":guard_token" -> Tr.s("item.dynamicvillages.guard_token.desc",
                    "Patrols the village and fights threats.");
            case DynamicVillages.MODID + ":rancher_token" -> Tr.s("item.dynamicvillages.rancher_token.desc",
                    "Cares for animals in a ranch.");
            case DynamicVillages.MODID + ":cook_token" -> Tr.s("item.dynamicvillages.cook_token.desc",
                    "Prepares meals in a kitchen.");
            case DynamicVillages.MODID + ":butcher_token" -> Tr.s("item.dynamicvillages.butcher_token.desc",
                    "Turns surplus livestock into food.");
            case DynamicVillages.MODID + ":miner_token" -> Tr.s("item.dynamicvillages.miner_token.desc",
                    "Digs stone and ores from a mine.");
            case DynamicVillages.MODID + ":archer_token" -> Tr.s("item.dynamicvillages.archer_token.desc",
                    "Defends the village with a bow.");
            case DynamicVillages.MODID + ":mage_token" -> Tr.s("item.dynamicvillages.mage_token.desc",
                    "Casts magic bolts at monsters from behind the guards. Needs a Barracks.");
            case DynamicVillages.MODID + ":captain_token" -> Tr.s("item.dynamicvillages.captain_token.desc",
                    "The barracks' elite guard: trains guards and leads the fight. One per Barracks.");
            case DynamicVillages.MODID + ":tradesman_token" -> Tr.s("item.dynamicvillages.tradesman_token.desc",
                    "Sells surplus to the visiting merchant.");
            case DynamicVillages.MODID + ":bard_token" -> Tr.s("item.dynamicvillages.bard_token.desc",
                    "Plays violin, flute, bass or guitar to raise village spirits.");
            case DynamicVillages.MODID + ":blacksmith_token" -> Tr.s("item.dynamicvillages.blacksmith_token.desc",
                    "Makes tools and armor at a forge.");
            case DynamicVillages.MODID + ":teacher_token" -> Tr.s("item.dynamicvillages.teacher_token.desc",
                    "Teaches villagers at a school.");
            case DynamicVillages.MODID + ":builder_token" -> Tr.s("item.dynamicvillages.builder_token.desc",
                    "Builds what you pick at its desk, using blocks from storage or from you. "
                    + "With nothing to build, it checks that buildings still work.");
            case DynamicVillages.MODID + ":cleric_token" -> Tr.s("item.dynamicvillages.cleric_token.desc",
                    "Throws healing orbs at the hurt, brings food to the hungry and cheers up the sad.");
            case DynamicVillages.MODID + ":druid_token" -> Tr.s("item.dynamicvillages.druid_token.desc",
                    "Speeds up crops and saplings, and regrows worked-out mines.");
            case DynamicVillages.MODID + ":enchanter_token" -> Tr.s("item.dynamicvillages.enchanter_token.desc",
                    "Writes enchanted books for the village's tools at a library.");
            default -> "";
        };
    }
}
