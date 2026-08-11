package com.github.ovaware.dealmaker.registry;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.storage.DealmakerPlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;

public final class DealmakerCapabilities {
    public static final Capability<DealmakerPlayerData> PLAYER_DATA = CapabilityManager.get(new CapabilityToken<>() {});
    public static final ResourceLocation PLAYER_DATA_ID = new ResourceLocation(DealmakerMod.MODID, "player_data");

    private DealmakerCapabilities() {}

    public static DealmakerPlayerData data(Player player) {
        return player.getCapability(PLAYER_DATA).orElseThrow(() -> new IllegalStateException("Missing Dealmaker player data"));
    }

    public static final class Provider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
        private final DealmakerPlayerData data = new DealmakerPlayerData();
        private final LazyOptional<DealmakerPlayerData> optional = LazyOptional.of(() -> data);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, net.minecraft.core.Direction side) {
            return capability == PLAYER_DATA ? optional.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            return (CompoundTag) DealmakerPlayerData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, data)
                    .result().orElseGet(CompoundTag::new);
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            DealmakerPlayerData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().ifPresent(loaded -> {
                data.deals().clear();
                data.deals().addAll(loaded.deals());
                data.storedSouls().clear();
                data.storedSouls().addAll(loaded.storedSouls());
                data.setSoulClaimed(loaded.soulClaimed());
                data.ledger().clear();
                data.ledger().addAll(loaded.ledger());
                data.setDealmaker(loaded.dealmaker());
            });
        }
    }
}
