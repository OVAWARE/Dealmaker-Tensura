package com.github.ovaware.dealmaker.deal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-owned metadata on a signed book. Client text alone never authorizes a deal. */
final class DealBook {
    private static final String PENDING_ID = "DevilBargenPendingId";
    private static final String DEAL_ID = "DevilBargenDealId";
    private static final String DEALMAKER_ID = "DevilBargenDealmakerId";

    private DealBook() {}

    static UUID markPending(ItemStack book) {
        UUID id = UUID.randomUUID();
        CompoundTag tag = tag(book);
        tag.putUUID(PENDING_ID, id);
        return id;
    }

    static boolean isManaged(ItemStack book) {
        CompoundTag tag = book.getTag();
        if (tag == null) return false;
        return tag.hasUUID(PENDING_ID) || tag.hasUUID(DEAL_ID);
    }

    static void clearPending(ItemStack book) {
        CompoundTag tag = tag(book);
        tag.remove(PENDING_ID);
    }

    static void bind(ItemStack book, Deal deal) {
        CompoundTag tag = tag(book);
        tag.remove(PENDING_ID);
        tag.putUUID(DEAL_ID, deal.id());
        tag.putUUID(DEALMAKER_ID, deal.dealmakerId());
    }

    static Optional<ItemStack> findPending(ServerPlayer player, UUID pendingId) {
        return find(player, tag -> tag.hasUUID(PENDING_ID) && pendingId.equals(tag.getUUID(PENDING_ID)));
    }

    static Optional<ItemStack> findDeal(ServerPlayer player, UUID dealId, UUID dealmakerId) {
        return find(player, tag -> tag.hasUUID(DEAL_ID) && tag.hasUUID(DEALMAKER_ID)
                && dealId.equals(tag.getUUID(DEAL_ID)) && dealmakerId.equals(tag.getUUID(DEALMAKER_ID)));
    }

    /** Main hand first, then off hand: a bound contract book authored by this player. */
    static Optional<ItemStack> findHeldDeal(ServerPlayer player) {
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            CompoundTag tag = stack.getTag();
            if (tag == null) continue;
            if (tag.hasUUID(DEAL_ID) && tag.hasUUID(DEALMAKER_ID)
                    && player.getUUID().equals(tag.getUUID(DEALMAKER_ID))) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    static Optional<UUID> dealId(ItemStack book) {
        CompoundTag tag = book.getTag();
        if (tag == null) return Optional.empty();
        return tag.hasUUID(DEAL_ID) ? Optional.of(tag.getUUID(DEAL_ID)) : Optional.empty();
    }

    private static Optional<ItemStack> find(ServerPlayer player, java.util.function.Predicate<CompoundTag> match) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            CompoundTag tag = stack.getTag();
            if (tag != null && match.test(tag)) return Optional.of(stack);
        }
        return Optional.empty();
    }

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrCreateTag();
    }
}
