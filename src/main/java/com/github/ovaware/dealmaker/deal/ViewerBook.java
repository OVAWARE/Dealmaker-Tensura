package com.github.ovaware.dealmaker.deal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Marks written books that mirror a dealmaker's ledger or active-deals view. */
public final class ViewerBook {
    enum Kind {
        LEDGER("ledger"),
        DEALS("deals");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Optional<Kind> fromId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) return Optional.of(kind);
            }
            return Optional.empty();
        }
    }

    private static final String KIND = "DevilBargenViewer";
    private static final String BOUND_TO = "DevilBargenViewerBoundTo";
    private static final String FINGERPRINT = "DevilBargenViewerFingerprint";

    private ViewerBook() {}

    public static boolean isViewer(ItemStack stack) {
        return kind(stack).isPresent();
    }

    public static Optional<Kind> kind(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return Optional.empty();
        if (!tag.contains(KIND)) return Optional.empty();
        return Kind.fromId(tag.getString(KIND));
    }

    static void mark(ItemStack stack, Kind kind, java.util.UUID boundTo, String fingerprint) {
        CompoundTag tag = tag(stack);
        tag.putString(KIND, kind.id());
        tag.putUUID(BOUND_TO, boundTo);
        tag.putString(FINGERPRINT, fingerprint);
    }

    static Optional<java.util.UUID> boundTo(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return Optional.empty();
        return tag.hasUUID(BOUND_TO) ? Optional.of(tag.getUUID(BOUND_TO)) : Optional.empty();
    }

    static String fingerprint(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(FINGERPRINT);
    }

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrCreateTag();
    }
}
