package com.github.ovaware.dealmaker.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ClaimedSoulItem extends Item {
    private static final String SOUL_UUID = "SoulOwner";
    private static final String SOUL_NAME = "SoulName";

    public ClaimedSoulItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(Item item, UUID owner, String name) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putUUID(SOUL_UUID, owner);
        tag.putString(SOUL_NAME, name);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name + "'s Soul").withStyle(ChatFormatting.AQUA));
        return stack;
    }

    public static Optional<UUID> owner(ItemStack stack) {
        if (!(stack.getItem() instanceof ClaimedSoulItem)) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.copyTag().hasUUID(SOUL_UUID)) return Optional.empty();
        return Optional.of(data.copyTag().getUUID(SOUL_UUID));
    }

    /** Bundles and other item-backed containers must never be able to nest a soul. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public void onDestroyed(ItemEntity entity) {
        owner(entity.getItem()).ifPresent(owner -> {
            if (!entity.level().isClientSide && entity.level().getServer() != null) {
                com.github.ovaware.dealmaker.deal.DealService.forceReturnSoul(entity.level().getServer(), owner);
            }
        });
        super.onDestroyed(entity);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        owner(stack).ifPresent(id -> tooltip.add(Component.literal("Bound to " + id).withStyle(ChatFormatting.DARK_GRAY)));
        tooltip.add(Component.literal("Must remain in an inventory or Dealmaker storage.").withStyle(ChatFormatting.GRAY));
    }
}
