package com.github.ovaware.dealmaker;

import com.github.ovaware.dealmaker.command.DealmakerCommands;
import com.github.ovaware.dealmaker.config.DealmakerConfigs;
import com.github.ovaware.dealmaker.event.DealmakerEvents;
import com.github.ovaware.dealmaker.registry.DealmakerAttachments;
import com.github.ovaware.dealmaker.registry.DealmakerItems;
import com.github.ovaware.dealmaker.registry.DealmakerSkills;
import com.mojang.logging.LogUtils;
import io.github.manasmods.manascore.skill.api.SkillEvents;
import dev.architectury.event.EventResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mod(DealmakerMod.MODID)
public final class DealmakerMod {
    public static final String MODID = "dealmaker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DealmakerMod(IEventBus modBus) {
        DealmakerAttachments.TYPES.register(modBus);
        DealmakerItems.init(modBus);
        DealmakerConfigs.init();
        DealmakerSkills.init();
        modBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(DealmakerCommands::register);
        NeoForge.EVENT_BUS.addListener(DealmakerMod::onAddReloadListener);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onLivingDamagePost);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onServerChat);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onItemEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onItemToss);
        NeoForge.EVENT_BUS.addListener(DealmakerEvents::onContainerClose);
        SkillEvents.ACTIVATE_SKILL.register((instance, entity, keyNumber, mode) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player && instance.isPresent()
                    && instance.get().getSkill() instanceof io.github.manasmods.tensura.ability.magic.Magic magic
                    && magic.getType() == io.github.manasmods.tensura.ability.magic.Magic.MagicType.SPIRITUAL
                    && !com.github.ovaware.dealmaker.deal.DealService.hasSoul(player)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("You cannot use Spiritual magic without your soul.")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return EventResult.interruptFalse();
            }
            if (entity instanceof net.minecraft.server.level.ServerPlayer player && instance.isPresent()) {
                com.github.ovaware.dealmaker.deal.DealService.onSkillUsed(player, instance.get());
            }
            return EventResult.pass();
        });
        SkillEvents.UNLOCK_SKILL.register((instance, entity, message) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player
                    && instance.getSkill() instanceof io.github.manasmods.tensura.ability.magic.Magic magic
                    && magic.getType() == io.github.manasmods.tensura.ability.magic.Magic.MagicType.SPIRITUAL
                    && !com.github.ovaware.dealmaker.deal.DealService.hasSoul(player)) {
                message.set(net.minecraft.network.chat.Component.literal("You cannot learn Spiritual magic without your soul.")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        SkillEvents.SKILL_MASTERY.register((instance, entity, mastery) -> {
            if (instance.getTag() != null && instance.getTag().getBoolean(com.github.ovaware.dealmaker.deal.DealService.SHARED_SKILL_TAG)) {
                mastery.set(0.0D);
                instance.setMastery(0.0D);
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(DealmakerConfigs::addToReincarnationPool);
    }

    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier,
                                                   net.minecraft.server.packs.resources.ResourceManager resourceManager,
                                                   net.minecraft.util.profiling.ProfilerFiller preparationsProfiler,
                                                   net.minecraft.util.profiling.ProfilerFiller reloadProfiler,
                                                   Executor backgroundExecutor, Executor gameExecutor) {
                return barrier.wait(null).thenRunAsync(DealmakerConfigs::reload, gameExecutor);
            }

            @Override
            public String getName() {
                return "Dealmaker configuration";
            }
        });
    }
}
