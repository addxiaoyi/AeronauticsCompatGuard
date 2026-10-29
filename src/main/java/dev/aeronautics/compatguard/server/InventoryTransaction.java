package dev.aeronautics.compatguard.server;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.Objects;
import java.util.UUID;

public final class InventoryTransaction {
    private InventoryTransaction() {}

    public static ItemStack insertReserved(
            CompatibilityServerState state,
            UUID requestId,
            String itemId,
            int amount,
            IItemHandler handler,
            ItemStack offered
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(handler, "handler");
        if (offered == null || offered.isEmpty() || amount <= 0 || offered.getCount() < amount) {
            return offered == null ? ItemStack.EMPTY : offered;
        }

        CompatibilityServerState.Reservation reservation = state.reservations().stream()
                .filter(value -> value.requestId().equals(requestId)
                        && value.itemId().equals(itemId)
                        && value.state() == CompatibilityServerState.ReservationState.RESERVED)
                .findFirst()
                .orElse(null);
        if (reservation == null || amount != reservation.amount()) {
            return offered;
        }

        ItemStack candidate = offered.copyWithCount(amount);
        ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(handler, candidate.copy(), true);
        int simulatedAccepted = amount - simulatedRemainder.getCount();
        if (simulatedAccepted <= 0) {
            return offered;
        }

        if (!state.beginInFlight(requestId, itemId, amount)) {
            return offered;
        }
        ItemStack remainder;
        try {
            remainder = ItemHandlerHelper.insertItemStacked(handler, candidate, false);
        } catch (RuntimeException error) {
            state.recordQuarantine(
                    "inventory_transaction",
                    requestId.toString(),
                    "unknown",
                    "handler threw after transaction intent was persisted; request was not replayed",
                    "item=" + itemId + ", acceptedPlanned=" + simulatedAccepted
                            + ", offered=" + offered + ", failure=" + error);
            ItemStack safeRemainder = simulatedRemainder.copy();
            safeRemainder.grow(offered.getCount() - amount);
            return safeRemainder;
        }
        int committed = amount - remainder.getCount();
        state.settleInFlight(requestId, itemId, committed);

        int remainderOffered = offered.getCount() - amount;
        ItemStack result = remainder.copy();
        result.grow(remainderOffered);
        return result;
    }

    public static ItemStack extractReserved(
            CompatibilityServerState state,
            UUID requestId,
            String itemId,
            int amount,
            IItemHandler handler
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(handler, "handler");
        if (amount <= 0 || itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }

        CompatibilityServerState.Reservation reservation = state.reservations().stream()
                .filter(value -> value.requestId().equals(requestId)
                        && value.itemId().equals(itemId)
                        && value.state() == CompatibilityServerState.ReservationState.RESERVED)
                .findFirst()
                .orElse(null);
        if (reservation == null || amount != reservation.amount()) {
            return ItemStack.EMPTY;
        }

        ItemStack simulated = extractMatching(handler, itemId, amount, true);
        if (simulated.isEmpty() || simulated.getCount() != amount
                || !state.beginInFlight(requestId, itemId, amount)) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted;
        try {
            extracted = extractMatching(handler, itemId, amount, false);
        } catch (RuntimeException error) {
            state.scheduleRetry(requestId);
            state.recordQuarantine("inventory_transaction", requestId.toString(), "unknown",
                    "handler threw during extraction; reservation moved to retry",
                    "item=" + itemId + ", amount=" + amount + ", failure=" + error);
            return ItemStack.EMPTY;
        }
        if (extracted.isEmpty()) {
            state.scheduleRetry(requestId);
            return ItemStack.EMPTY;
        }
        if (extracted.getCount() != amount) {
            state.settleInFlight(requestId, itemId, extracted.getCount());
            state.scheduleRetry(requestId);
            return extracted;
        }
        state.settleInFlight(requestId, itemId, amount);
        return extracted;
    }

    private static ItemStack extractMatching(IItemHandler handler, String itemId, int amount, boolean simulate) {
        ItemStack extracted = ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots() && extracted.getCount() < amount; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty() || !stack.getItem().builtInRegistryHolder().key().location().toString().equals(itemId)) {
                continue;
            }
            int needed = amount - extracted.getCount();
            ItemStack part = handler.extractItem(slot, needed, simulate);
            if (part.isEmpty()) {
                continue;
            }
            if (extracted.isEmpty()) {
                extracted = part.copy();
            } else if (ItemHandlerHelper.canItemStacksStack(extracted, part)) {
                extracted.grow(part.getCount());
            } else {
                return ItemStack.EMPTY;
            }
        }
        return extracted;
    }
}
