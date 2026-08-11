package com.github.ovaware.dealmaker.deal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A small, explicit predicate language emitted by the AI; it is never executable text. */
public record DealCondition(DealConditionType type, Party party, String assetId, int amount, String slot,
                            String dimensionId, double x, double y, double z, double radius,
                            boolean useX, boolean useY, boolean useZ, boolean negated, ConditionLogic logic,
                            java.util.List<DealConditionTerm> additionalTerms) {
    public static final DealCondition ALWAYS = new DealCondition(DealConditionType.ALWAYS, Party.ACCEPTOR, "", 0);

    /** Kept for existing saved data, tests, and callers that do not select an equipment slot. */
    public DealCondition(DealConditionType type, Party party, String assetId, int amount) {
        this(type, party, assetId, amount, "", "", 0.0, 0.0, 0.0, 0.0);
    }

    public DealCondition(DealConditionType type, Party party, String assetId, int amount, String slot) {
        this(type, party, assetId, amount, slot, "", 0.0, 0.0, 0.0, 0.0);
    }

    public DealCondition(DealConditionType type, Party party, String assetId, int amount, String slot,
                         String dimensionId, double x, double y, double z, double radius) {
        this(type, party, assetId, amount, slot, dimensionId, x, y, z, radius,
                true, true, true, false, ConditionLogic.ALL, java.util.List.of());
    }

    public static Codec<DealCondition> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<DealCondition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.xmap(DealConditionType::valueOf, DealConditionType::name).fieldOf("type").forGetter(DealCondition::type),
                Codec.STRING.xmap(Party::valueOf, Party::name).fieldOf("party").forGetter(DealCondition::party),
                Codec.STRING.optionalFieldOf("asset", "").forGetter(DealCondition::assetId),
                Codec.INT.optionalFieldOf("amount", 0).forGetter(DealCondition::amount),
                Codec.STRING.optionalFieldOf("slot", "").forGetter(DealCondition::slot),
                Codec.STRING.optionalFieldOf("dimension", "").forGetter(DealCondition::dimensionId),
                Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(DealCondition::x),
                Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(DealCondition::y),
                Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(DealCondition::z),
                Codec.DOUBLE.optionalFieldOf("radius", 0.0).forGetter(DealCondition::radius),
                Codec.BOOL.optionalFieldOf("use_x", true).forGetter(DealCondition::useX),
                Codec.BOOL.optionalFieldOf("use_y", true).forGetter(DealCondition::useY),
                Codec.BOOL.optionalFieldOf("use_z", true).forGetter(DealCondition::useZ),
                Codec.BOOL.optionalFieldOf("negated", false).forGetter(DealCondition::negated),
                Codec.STRING.xmap(ConditionLogic::valueOf, ConditionLogic::name).optionalFieldOf("logic", ConditionLogic.ALL).forGetter(DealCondition::logic),
                DealConditionTerm.codec().listOf().optionalFieldOf("additional_terms", java.util.List.of()).forGetter(DealCondition::additionalTerms)
        ).apply(instance, DealCondition::new));
    }

    public java.util.List<DealConditionTerm> terms() {
        java.util.ArrayList<DealConditionTerm> terms = new java.util.ArrayList<>();
        terms.add(new DealConditionTerm(type, party, assetId, amount, slot, dimensionId, x, y, z, radius,
                useX, useY, useZ, negated));
        terms.addAll(additionalTerms);
        return java.util.List.copyOf(terms);
    }
}
