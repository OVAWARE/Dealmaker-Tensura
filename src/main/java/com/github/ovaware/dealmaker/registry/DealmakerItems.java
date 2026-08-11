package com.github.ovaware.dealmaker.registry;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class DealmakerItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DealmakerMod.MODID);
    public static final RegistryObject<ClaimedSoulItem> CLAIMED_SOUL = ITEMS.register("claimed_soul",
            () -> new ClaimedSoulItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    private DealmakerItems() {}

    public static void init(IEventBus bus) {
        ITEMS.register(bus);
    }
}
