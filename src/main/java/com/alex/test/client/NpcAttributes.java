package com.alex.test.client;

import com.alex.test.DynamicVillages;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

public class NpcAttributes {

    @SubscribeEvent
    public static void createDefaultAttributes(EntityAttributeCreationEvent event) {
        // 30 HP puts a villager above a zombie's 20, so a guard trades
        // favourably one-on-one and can win a small fight while a swarm still
        // overwhelms it. The ARMOR/ARMOR_TOUGHNESS lines below are kept only
        // to state the intent: vanilla's createLivingAttributes() already
        // registers both, so they change nothing. (An earlier comment here
        // claimed armour did nothing for want of the attribute -- that was
        // wrong; the health raise is what actually buffed guards.)
        event.put(
                DynamicVillages.NPC.get(),
                LivingEntity.createLivingAttributes()
                        .add(Attributes.MAX_HEALTH, 30.0)
                        .add(Attributes.MOVEMENT_SPEED, 0.5)
                        .add(Attributes.FOLLOW_RANGE, 32.0)
                        .add(Attributes.ARMOR, 0.0)
                        .add(Attributes.ARMOR_TOUGHNESS, 0.0)
                        .build());
        // Merchants and nomads arrive from near the edge of the simulation
        // distance, which can be hundreds of blocks from the Town Hall.
        event.put(
                DynamicVillages.VISITOR.get(),
                LivingEntity.createLivingAttributes()
                        .add(Attributes.MAX_HEALTH, 20.0)
                        .add(Attributes.MOVEMENT_SPEED, 0.5)
                        .add(Attributes.FOLLOW_RANGE, 256.0)
                        .build());
        event.put(
                DynamicVillages.NOMAD.get(),
                LivingEntity.createLivingAttributes()
                        .add(Attributes.MAX_HEALTH, 20.0)
                        .add(Attributes.MOVEMENT_SPEED, 0.5)
                        .add(Attributes.FOLLOW_RANGE, 256.0)
                        .build());
        event.put(DynamicVillages.RAID_WARLORD.get(),
                com.alex.test.village.raid.warlord.RaidWarlord.createAttributes().build());
        event.put(DynamicVillages.MINI_MERCHANT_PREVIEW.get(),
                com.alex.test.entity.MiniMerchantPreview.createAttributes().build());
        event.put(DynamicVillages.MID_MERCHANT_PREVIEW.get(),
                com.alex.test.entity.MiniMerchantPreview.createAttributes().build());
        event.put(
                DynamicVillages.MAYOR.get(),
                LivingEntity.createLivingAttributes()
                        .add(Attributes.MAX_HEALTH, 20.0)
                        .add(Attributes.MOVEMENT_SPEED, 0.5)
                        .add(Attributes.FOLLOW_RANGE, 32.0)
                        .build());
    }
}
