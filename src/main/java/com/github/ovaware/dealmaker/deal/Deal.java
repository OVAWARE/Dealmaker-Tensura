package com.github.ovaware.dealmaker.deal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Deal(UUID id, UUID dealmakerId, UUID acceptorId, String originalText,
                   List<DealClause> clauses, DealStatus status, long createdAt,
                   long nextDueAt, List<AttributeGrant> attributeGrants, Map<String, Integer> conditionValues,
                   int dataVersion, Map<String, String> runtimeValues) {
    public static final int CURRENT_DATA_VERSION = 4;
    public Deal(UUID id, UUID dealmakerId, UUID acceptorId, String originalText,
                List<DealClause> clauses, DealStatus status, long createdAt, long nextDueAt) {
        this(id, dealmakerId, acceptorId, originalText, clauses, status, createdAt, nextDueAt,
                List.of(), Map.of(), CURRENT_DATA_VERSION, Map.of());
    }
    public static Codec<Deal> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
        private static final Codec<Deal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUID_CODEC.fieldOf("id").forGetter(Deal::id),
                UUID_CODEC.fieldOf("dealmaker").forGetter(Deal::dealmakerId),
                UUID_CODEC.fieldOf("acceptor").forGetter(Deal::acceptorId),
                Codec.STRING.fieldOf("text").forGetter(Deal::originalText),
                DealClause.codec().listOf().fieldOf("clauses").forGetter(Deal::clauses),
                Codec.STRING.xmap(DealStatus::valueOf, DealStatus::name).fieldOf("status").forGetter(Deal::status),
                Codec.LONG.fieldOf("created_at").forGetter(Deal::createdAt),
                Codec.LONG.optionalFieldOf("next_due_at", 0L).forGetter(Deal::nextDueAt),
                AttributeGrant.codec().listOf().optionalFieldOf("attribute_grants", List.of()).forGetter(Deal::attributeGrants),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("condition_values", Map.of()).forGetter(Deal::conditionValues),
                Codec.INT.optionalFieldOf("data_version", 1).forGetter(Deal::dataVersion),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("runtime_values", Map.of()).forGetter(Deal::runtimeValues)
        ).apply(instance, Deal::new));
    }

    public Deal withStatus(DealStatus newStatus) {
        return copy(newStatus, nextDueAt, attributeGrants, conditionValues, runtimeValues);
    }

    public Deal withNextDueAt(long tick) {
        return copy(status, tick, attributeGrants, conditionValues, runtimeValues);
    }

    public Deal withAcceptor(UUID playerId) {
        return new Deal(id, dealmakerId, playerId, originalText, clauses, status, createdAt, nextDueAt,
                attributeGrants, conditionValues, CURRENT_DATA_VERSION, runtimeValues);
    }

    public boolean hasAcceptor() {
        return !acceptorId.equals(new UUID(0L, 0L));
    }

    public Deal withAttributeGrants(List<AttributeGrant> grants) {
        return copy(status, nextDueAt, List.copyOf(grants), conditionValues, runtimeValues);
    }

    public Deal withConditionValues(Map<String, Integer> values) {
        return copy(status, nextDueAt, attributeGrants, Map.copyOf(values), runtimeValues);
    }

    public Deal withRuntimeValues(Map<String, String> values) {
        return copy(status, nextDueAt, attributeGrants, conditionValues, Map.copyOf(values));
    }

    public Deal migratedStatus() {
        if (dataVersion >= CURRENT_DATA_VERSION) return this;
        DealStatus migrated = status == DealStatus.PENDING || status == DealStatus.ACTIVE
                ? DealStatus.INVALIDATED_LEGACY : status;
        return new Deal(id, dealmakerId, acceptorId, originalText, clauses, migrated, createdAt, nextDueAt,
                attributeGrants, conditionValues, CURRENT_DATA_VERSION, runtimeValues);
    }

    private Deal copy(DealStatus newStatus, long due, List<AttributeGrant> grants,
                      Map<String, Integer> values, Map<String, String> runtime) {
        return new Deal(id, dealmakerId, acceptorId, originalText, clauses, newStatus, createdAt, due,
                grants, values, CURRENT_DATA_VERSION, runtime);
    }
}
