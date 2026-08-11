package com.github.ovaware.dealmaker.deal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Data-only instruction produced by a parser. It cannot execute itself. Empty fields are
 * intentional: the validator defines which fields each kind is allowed to use.
 */
public record DealClause(ClauseKind kind, Party from, Party to, String assetId,
                         double amount, long periodTicks, DealTrigger trigger, DealCondition condition) {
    public DealClause(ClauseKind kind, Party from, Party to, String assetId, double amount, long periodTicks) {
        this(kind, from, to, assetId, amount, periodTicks,
                kind == ClauseKind.RECURRING_ITEM_PAYMENT ? DealTrigger.ON_RECURRING_DUE : DealTrigger.ON_ACCEPTANCE,
                DealCondition.ALWAYS);
    }

    public static Codec<DealClause> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<DealClause> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.xmap(ClauseKind::valueOf, ClauseKind::name).fieldOf("kind").forGetter(DealClause::kind),
                Codec.STRING.xmap(Party::valueOf, Party::name).fieldOf("from").forGetter(DealClause::from),
                Codec.STRING.xmap(Party::valueOf, Party::name).fieldOf("to").forGetter(DealClause::to),
                Codec.STRING.optionalFieldOf("asset", "").forGetter(DealClause::assetId),
                Codec.DOUBLE.optionalFieldOf("amount", 0.0).forGetter(DealClause::amount),
                Codec.LONG.optionalFieldOf("period_ticks", 0L).forGetter(DealClause::periodTicks),
                Codec.STRING.xmap(DealTrigger::valueOf, DealTrigger::name).optionalFieldOf("trigger", DealTrigger.ON_ACCEPTANCE)
                        .forGetter(DealClause::trigger),
                DealCondition.codec().optionalFieldOf("condition", DealCondition.ALWAYS).forGetter(DealClause::condition)
        ).apply(instance, DealClause::new));
    }
}
