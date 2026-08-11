package com.github.ovaware.dealmaker.registry;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DealmakerItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DealmakerMod.MODID);
    public static final DeferredHolder<Item, ClaimedSoulItem> CLAIMED_SOUL = ITEMS.register("claimed_soul",
            () -> new ClaimedSoulItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    private DealmakerItems() {}

    public static void init(IEventBus bus) {
        ITEMS.register(bus);
    }
}
