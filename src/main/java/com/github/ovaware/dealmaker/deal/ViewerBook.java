package com.github.ovaware.dealmaker.deal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

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
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag tag = data.copyTag();
        if (!tag.contains(KIND)) return Optional.empty();
        return Kind.fromId(tag.getString(KIND));
    }

    static void mark(ItemStack stack, Kind kind, java.util.UUID boundTo, String fingerprint) {
        CompoundTag tag = tag(stack);
        tag.putString(KIND, kind.id());
        tag.putUUID(BOUND_TO, boundTo);
        tag.putString(FINGERPRINT, fingerprint);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    static Optional<java.util.UUID> boundTo(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag tag = data.copyTag();
        return tag.hasUUID(BOUND_TO) ? Optional.of(tag.getUUID(BOUND_TO)) : Optional.empty();
    }

    static String fingerprint(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(FINGERPRINT);
    }

    private static CompoundTag tag(ItemStack stack) {
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        return existing == null ? new CompoundTag() : existing.copyTag();
    }
}
