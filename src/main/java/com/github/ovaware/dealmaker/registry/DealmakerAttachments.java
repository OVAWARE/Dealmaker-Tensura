package com.github.ovaware.dealmaker.registry;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.storage.DealmakerPlayerData;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class DealmakerAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DealmakerMod.MODID);
    public static final Supplier<AttachmentType<DealmakerPlayerData>> PLAYER_DATA = TYPES.register("player_data",
            () -> AttachmentType.builder(DealmakerPlayerData::new)
                    .serialize(DealmakerPlayerData.CODEC)
                    .sync(ByteBufCodecs.fromCodecWithRegistries(DealmakerPlayerData.CODEC))
                    .copyOnDeath()
                    .build());

    private DealmakerAttachments() {}
}
