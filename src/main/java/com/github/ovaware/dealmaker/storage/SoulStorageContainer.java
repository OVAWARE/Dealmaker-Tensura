package com.github.ovaware.dealmaker.storage;

import com.github.ovaware.dealmaker.item.ClaimedSoulItem;
import com.github.ovaware.dealmaker.registry.DealmakerCapabilities;
import com.github.ovaware.dealmaker.registry.DealmakerItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.UUID;

public final class SoulStorageContainer extends SimpleContainer {
    private final ServerPlayer owner;
    private boolean loading = true;

    public SoulStorageContainer(ServerPlayer owner) {
        super(27);
        this.owner = owner;
        int slot = 0;
        for (UUID soul : DealmakerCapabilities.data(owner).storedSouls()) {
            if (slot >= getContainerSize()) break;
            String name = owner.server.getProfileCache().get(soul).map(profile -> profile.getName()).orElse(soul.toString());
            setItem(slot++, ClaimedSoulItem.create(DealmakerItems.CLAIMED_SOUL.get(), soul, name));
        }
        loading = false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return ClaimedSoulItem.owner(stack).isPresent();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (loading) return;
        LinkedHashSet<UUID> souls = new LinkedHashSet<>();
        for (int slot = 0; slot < getContainerSize(); slot++) {
            ClaimedSoulItem.owner(getItem(slot)).ifPresent(souls::add);
        }
        var stored = DealmakerCapabilities.data(owner).storedSouls();
        stored.clear();
        stored.addAll(souls);
    }
}
