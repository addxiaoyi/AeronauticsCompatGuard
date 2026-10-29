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
        return insertReserved(state, requestId, itemId, amount, handler, offered, null);
    }

    /**
     * Inserts {@code amount} items that are already covered by a server-side reservation.
     *
     * <p>Contract: exactly-once. The ledger is moved to {@code IN_FLIGHT} before the handler is
     * touched and is settled afterwards. When the handler throws after the intent was persisted the
     * real commit result is unknown; the reservation is parked in {@code SUSPECT} instead of being
     * silently retried, because replaying an unknown outcome duplicates items.</p>
     */
    public static ItemStack insertReserved(
            CompatibilityServerState state,
            UUID requestId,
            String itemId,
            int amount,
            IItemHandler handler,
            ItemStack offered,
            Runnable retryAction
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
            // Commit result is unknown: park the reservation so no automatic replay can duplicate items.
            state.markSuspect(requestId, itemId,
                    "handler threw after transaction intent was persisted; commit result unknown",
                    "item=" + itemId + ", acceptedPlanned=" + simulatedAccepted
                            + ", offered=" + offered + ", failure=" + error);
            ItemStack safeRemainder = simulatedRemainder.copy();
            safeRemainder.grow(offered.getCount() - amount);
            return safeRemainder;
        }
        int committed = amount - remainder.getCount();
        if (!state.settleInFlight(requestId, itemId, committed)) {
            state.markSuspect(requestId, itemId,
                    "ledger settle failed after insertion; commit result diverged from the ledger",
                    "item=" + itemId + ", committed=" + committed + ", remainder=" + remainder);
        }
        if (committed < amount && retryAction != null) {
            state.registerRetryAction(requestId, retryAction);
        }

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
        return extractReserved(state, requestId, itemId, amount, handler, null);
    }

    public static ItemStack extractReserved(
            CompatibilityServerState state,
            UUID requestId,
            String itemId,
            int amount,
            IItemHandler handler,
            Runnable retryAction
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
            state.markSuspect(requestId, itemId,
                    "handler threw during extraction; commit result unknown",
                    "item=" + itemId + ", amount=" + amount + ", failure=" + error);
            return ItemStack.EMPTY;
        }
        if (extracted.isEmpty()) {
            // Execute returned nothing: no commit happened, so the request can be retried safely.
            state.scheduleRetry(requestId);
            if (retryAction != null) {
                state.registerRetryAction(requestId, retryAction);
            }
            return ItemStack.EMPTY;
        }
        if (extracted.getCount() != amount) {
            state.settleInFlight(requestId, itemId, extracted.getCount());
            state.scheduleRetry(requestId);
            if (retryAction != null) {
                state.registerRetryAction(requestId, retryAction);
            }
            return extracted;
        }
        if (!state.settleInFlight(requestId, itemId, amount)) {
            state.markSuspect(requestId, itemId,
                    "ledger settle failed after extraction; commit result diverged from the ledger",
                    "item=" + itemId + ", extracted=" + extracted);
        }
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
            } else if (ItemStack.isSameItemSameComponents(extracted, part)) {
                extracted.grow(part.getCount());
            } else {
                return ItemStack.EMPTY;
            }
        }
        return extracted;
    }
}
