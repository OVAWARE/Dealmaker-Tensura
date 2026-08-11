package com.github.ovaware.dealmaker;

import com.github.ovaware.dealmaker.command.DealmakerCommands;
import com.github.ovaware.dealmaker.config.DealmakerConfigs;
import com.github.ovaware.dealmaker.event.DealmakerEvents;
import com.github.ovaware.dealmaker.registry.DealmakerItems;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mod(DealmakerMod.MODID)
public final class DealmakerMod {
    public static final String MODID = "dealmaker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DealmakerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DealmakerItems.init(modBus);
        DealmakerConfigs.init();
        modBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.addListener(DealmakerCommands::register);
        MinecraftForge.EVENT_BUS.addListener(DealmakerMod::onAddReloadListener);
        MinecraftForge.EVENT_BUS.register(DealmakerEvents.class);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
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
                return "Devil Bargen configuration";
            }
        });
    }
}
