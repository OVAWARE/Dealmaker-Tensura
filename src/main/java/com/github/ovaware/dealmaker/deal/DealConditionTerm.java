package com.github.ovaware.dealmaker.deal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** One leaf in a boolean condition group. */
public record DealConditionTerm(DealConditionType type, Party party, String assetId, int amount, String slot,
                                String dimensionId, double x, double y, double z, double radius,
                                boolean useX, boolean useY, boolean useZ, boolean negated) {
    public static Codec<DealConditionTerm> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<DealConditionTerm> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(DealConditionType::valueOf, DealConditionType::name).fieldOf("type").forGetter(DealConditionTerm::type),
                    Codec.STRING.xmap(Party::valueOf, Party::name).fieldOf("party").forGetter(DealConditionTerm::party),
                    Codec.STRING.optionalFieldOf("asset", "").forGetter(DealConditionTerm::assetId),
                    Codec.INT.optionalFieldOf("amount", 0).forGetter(DealConditionTerm::amount),
                    Codec.STRING.optionalFieldOf("slot", "").forGetter(DealConditionTerm::slot),
                    Codec.STRING.optionalFieldOf("dimension", "").forGetter(DealConditionTerm::dimensionId),
                    Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(DealConditionTerm::x),
                    Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(DealConditionTerm::y),
                    Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(DealConditionTerm::z),
                    Codec.DOUBLE.optionalFieldOf("radius", 0.0).forGetter(DealConditionTerm::radius),
                    Codec.BOOL.optionalFieldOf("use_x", true).forGetter(DealConditionTerm::useX),
                    Codec.BOOL.optionalFieldOf("use_y", true).forGetter(DealConditionTerm::useY),
                    Codec.BOOL.optionalFieldOf("use_z", true).forGetter(DealConditionTerm::useZ),
                    Codec.BOOL.optionalFieldOf("negated", false).forGetter(DealConditionTerm::negated)
            ).apply(instance, DealConditionTerm::new));
    }

    public DealCondition asCondition() {
        return new DealCondition(type, party, assetId, amount, slot, dimensionId, x, y, z, radius,
                useX, useY, useZ, negated, ConditionLogic.ALL, java.util.List.of());
    }
}
