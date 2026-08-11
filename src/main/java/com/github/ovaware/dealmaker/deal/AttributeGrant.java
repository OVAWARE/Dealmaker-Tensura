package com.github.ovaware.dealmaker.deal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

/** Persisted exact amount awarded by a deal, allowing a later breach to revoke only that grant. */
public record AttributeGrant(UUID sourceId, UUID recipientId, String attributeId, double amount) {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static Codec<AttributeGrant> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<AttributeGrant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUID_CODEC.fieldOf("source").forGetter(AttributeGrant::sourceId),
                UUID_CODEC.fieldOf("recipient").forGetter(AttributeGrant::recipientId),
                Codec.STRING.fieldOf("attribute").forGetter(AttributeGrant::attributeId),
                Codec.DOUBLE.fieldOf("amount").forGetter(AttributeGrant::amount)
        ).apply(instance, AttributeGrant::new));
    }
}
