package com.github.ovaware.dealmaker.storage;

import com.github.ovaware.dealmaker.deal.Deal;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DealmakerPlayerData {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    public static final Codec<DealmakerPlayerData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Deal.codec().listOf().optionalFieldOf("deals", List.of()).forGetter(DealmakerPlayerData::deals),
            UUID_CODEC.listOf().optionalFieldOf("stored_souls", List.of()).forGetter(DealmakerPlayerData::storedSouls),
            Codec.BOOL.optionalFieldOf("soul_claimed", false).forGetter(DealmakerPlayerData::soulClaimed),
            LedgerBook.codec().listOf().optionalFieldOf("ledger", List.of()).forGetter(DealmakerPlayerData::ledger),
            Codec.BOOL.optionalFieldOf("dealmaker", false).forGetter(DealmakerPlayerData::dealmaker)
    ).apply(instance, DealmakerPlayerData::new));

    private final List<Deal> deals;
    private final List<UUID> storedSouls;
    private boolean soulClaimed;
    private final List<LedgerBook> ledger;
    private boolean dealmaker;

    private DealmakerPlayerData(List<Deal> deals, List<UUID> storedSouls, boolean soulClaimed, List<LedgerBook> ledger,
                                boolean dealmaker) {
        this.deals = new ArrayList<>(deals.stream().map(Deal::migratedStatus).toList());
        this.storedSouls = new ArrayList<>(storedSouls.stream().distinct().limit(27).toList());
        this.soulClaimed = soulClaimed;
        this.ledger = new ArrayList<>(ledger.stream().limit(LedgerBook.MAX_ENTRIES).toList());
        this.dealmaker = dealmaker;
    }

    public DealmakerPlayerData() {
        this(List.of(), List.of(), false, List.of(), false);
    }

    public List<Deal> deals() {
        return deals;
    }

    public List<UUID> storedSouls() {
        return storedSouls;
    }

    public boolean soulClaimed() {
        return soulClaimed;
    }

    public void setSoulClaimed(boolean soulClaimed) {
        this.soulClaimed = soulClaimed;
    }

    public List<LedgerBook> ledger() {
        return ledger;
    }

    public boolean dealmaker() {
        return dealmaker;
    }

    public void setDealmaker(boolean dealmaker) {
        this.dealmaker = dealmaker;
    }
}
