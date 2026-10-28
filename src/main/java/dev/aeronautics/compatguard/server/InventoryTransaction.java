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
        if (reservation == null || amount > reservation.amount()) {
            return offered;
        }

        ItemStack candidate = offered.copyWithCount(amount);
        ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(handler, candidate.copy(), true);
        int simulatedAccepted = amount - simulatedRemainder.getCount();
        if (simulatedAccepted <= 0) {
            return offered;
        }

        state.markInFlight(requestId);
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, candidate, false);
        int committed = amount - remainder.getCount();
        if (!state.settleInFlight(requestId, itemId, committed)) {
            return offered;
        }

        int remainderOffered = offered.getCount() - amount;
        ItemStack result = remainder.copy();
        result.grow(remainderOffered);
        return result;
    }
}
