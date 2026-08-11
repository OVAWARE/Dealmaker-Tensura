package com.github.ovaware.dealmaker.event;

import com.github.ovaware.dealmaker.deal.DealService;
import com.github.ovaware.dealmaker.registry.DealmakerCapabilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;

public final class DealmakerEvents {
    private DealmakerEvents() {}

    @SubscribeEvent
    public static void attachPlayerData(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) event.addCapability(DealmakerCapabilities.PLAYER_DATA_ID, new DealmakerCapabilities.Provider());
    }

    @SubscribeEvent
    public static void copyPlayerData(PlayerEvent.Clone event) {
        event.getOriginal().reviveCaps();
        event.getOriginal().getCapability(DealmakerCapabilities.PLAYER_DATA).ifPresent(oldData ->
                event.getEntity().getCapability(DealmakerCapabilities.PLAYER_DATA).ifPresent(newData -> {
                    newData.deals().addAll(oldData.deals());
                    newData.storedSouls().addAll(oldData.storedSouls());
                    newData.setSoulClaimed(oldData.soulClaimed());
                    newData.ledger().addAll(oldData.ledger());
                }));
        event.getOriginal().invalidateCaps();
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player instanceof ServerPlayer player) {
            DealService.returnSoulsInIllegalContainers(player);
            DealService.refreshViewerBooks(player);
            DealService.tick(player);
        }
    }

    /** A soul is never permitted to exist loose in the world, even for one tick. */
    @SubscribeEvent
    public static void onItemEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ItemEntity item)) return;
        ClaimedSoulItem.owner(item.getItem()).ifPresent(owner -> {
            event.setCanceled(true);
            DealService.forceReturnSoul(event.getLevel().getServer(), owner);
        });
    }

    /** ItemTossEvent removes the stack before spawning it, so canceling alone would lose it. */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer().level().isClientSide) return;
        ClaimedSoulItem.owner(event.getEntity().getItem()).ifPresent(owner -> {
            event.setCanceled(true);
            DealService.forceReturnSoul(event.getPlayer().getServer(), owner);
        });
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DealService.returnSoulsInIllegalContainer(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer holder)) return;
        DealService.onPlayerDied(holder);
        if (event.getSource().getEntity() instanceof ServerPlayer killer && DealService.hasCustody(holder, killer.getUUID())) {
            DealService.removeSoul(holder, killer.getUUID());
            DealmakerCapabilities.data(killer).setSoulClaimed(false);
            killer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Your soul has been returned to you.")
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingHurtEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer victim)) return;
        event.setAmount(DealService.redirectIncomingDamage(victim, event.getSource(), event.getAmount()));
    }

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent event) {
        if (event.getAmount() <= 0.0F || !(event.getEntity() instanceof ServerPlayer victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        DealService.onPlayerHarmed(victim, attacker);
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!event.isCanceled()) DealService.onPlayerChat(event.getPlayer(), event.getRawText());
    }
}
