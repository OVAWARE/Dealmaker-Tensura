package com.github.ovaware.dealmaker.event;

import com.github.ovaware.dealmaker.deal.DealService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.minecraft.world.entity.item.ItemEntity;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;

public final class DealmakerEvents {
    private DealmakerEvents() {}

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DealService.returnSoulsInIllegalContainers(player);
            DealService.refreshViewerBooks(player);
            DealService.tick(player);
        }
    }

    /** A soul is never permitted to exist loose in the world, even for one tick. */
    public static void onItemEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ItemEntity item)) return;
        ClaimedSoulItem.owner(item.getItem()).ifPresent(owner -> {
            event.setCanceled(true);
            DealService.forceReturnSoul(event.getLevel().getServer(), owner);
        });
    }

    /** ItemTossEvent removes the stack before spawning it, so canceling alone would lose it. */
    public static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer().level().isClientSide) return;
        ClaimedSoulItem.owner(event.getEntity().getItem()).ifPresent(owner -> {
            event.setCanceled(true);
            DealService.forceReturnSoul(event.getPlayer().getServer(), owner);
        });
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DealService.returnSoulsInIllegalContainer(player, event.getContainer());
        }
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer holder)) return;
        DealService.onPlayerDied(holder);
        if (event.getSource().getEntity() instanceof ServerPlayer killer && DealService.hasCustody(holder, killer.getUUID())) {
            DealService.removeSoul(holder, killer.getUUID());
            killer.getData(com.github.ovaware.dealmaker.registry.DealmakerAttachments.PLAYER_DATA).setSoulClaimed(false);
            killer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Your soul has been returned to you.")
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer victim)) return;
        event.setAmount(DealService.redirectIncomingDamage(victim, event.getSource(), event.getAmount()));
    }

    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || !(event.getEntity() instanceof ServerPlayer victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        DealService.onPlayerHarmed(victim, attacker);
    }

    public static void onServerChat(ServerChatEvent event) {
        if (!event.isCanceled()) DealService.onPlayerChat(event.getPlayer(), event.getRawText());
    }
}
