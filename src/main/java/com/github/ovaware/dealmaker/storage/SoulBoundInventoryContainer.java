package com.github.ovaware.dealmaker.storage;

import com.github.ovaware.dealmaker.deal.DealService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Live custody-checking inventory view; losing the soul immediately revokes every slot. */
public final class SoulBoundInventoryContainer extends SimpleContainer {
    private final ServerPlayer holder;
    private final ServerPlayer owner;

    public SoulBoundInventoryContainer(ServerPlayer holder, ServerPlayer owner) {
        super(27);
        this.holder = holder;
        this.owner = owner;
    }

    private boolean authorized() {
        return holder.isAlive() && DealService.hasCustody(holder, owner.getUUID());
    }

    @Override public ItemStack getItem(int slot) {
        return authorized() && slot >= 0 && slot < 27 ? owner.getInventory().getItem(slot) : ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot, int amount) {
        return authorized() && slot >= 0 && slot < 27 ? owner.getInventory().removeItem(slot, amount) : ItemStack.EMPTY;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        return authorized() && slot >= 0 && slot < 27 ? owner.getInventory().removeItemNoUpdate(slot) : ItemStack.EMPTY;
    }
    @Override public void setItem(int slot, ItemStack stack) {
        if (authorized() && slot >= 0 && slot < 27) owner.getInventory().setItem(slot, stack);
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return authorized() && slot >= 0 && slot < 27;
    }
    @Override public boolean stillValid(Player player) {
        return player == holder && authorized();
    }
    @Override public void setChanged() {
        if (authorized()) owner.getInventory().setChanged();
    }
}
