package dev.aeronautics.compatguard.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.Objects;
import java.util.UUID;

/**
 * Reservation-backed fluid transfer, mirroring {@link InventoryTransaction}.
 *
 * <p>Fluid used to be listed as unimplemented because no supported public path was wired. The
 * NeoForge {@code IFluidHandler} contract ({@code fill}/{@code drain} with
 * {@code FluidAction.SIMULATE}) is a public API and is enough to give fluids the same exactly-once
 * behaviour as items: simulate, persist the intent, execute, settle, and park unknown outcomes in
 * {@code SUSPECT} instead of replaying them.</p>
 */
public final class FluidTransaction {
    private FluidTransaction() {}

    public static int fillReserved(
            CompatibilityServerState state,
            UUID requestId,
            String fluidId,
            int amount,
            IFluidHandler handler,
            FluidStack offered
    ) {
        return fillReserved(state, requestId, fluidId, amount, handler, offered, null);
    }

    public static int fillReserved(
            CompatibilityServerState state,
            UUID requestId,
            String fluidId,
            int amount,
            IFluidHandler handler,
            FluidStack offered,
            Runnable retryAction
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(handler, "handler");
        if (offered == null || offered.isEmpty() || amount <= 0 || offered.getAmount() < amount) {
            return 0;
        }
        if (!hasReservation(state, requestId, fluidId, amount)) {
            return 0;
        }

        FluidStack candidate = offered.copyWithAmount(amount);
        int simulated = handler.fill(candidate.copy(), FluidAction.SIMULATE);
        if (simulated <= 0) {
            return 0;
        }
        if (!state.beginInFlight(requestId, fluidId, amount)) {
            return 0;
        }

        int filled;
        try {
            filled = handler.fill(candidate, FluidAction.EXECUTE);
        } catch (RuntimeException error) {
            state.markSuspect(requestId, fluidId,
                    "fluid handler threw after transaction intent was persisted; commit result unknown",
                    "fluid=" + fluidId + ", amount=" + amount + ", planned=" + simulated
                            + ", failure=" + error);
            return 0;
        }
        if (!state.settleInFlight(requestId, fluidId, filled)) {
            state.markSuspect(requestId, fluidId,
                    "ledger settle failed after fluid fill; commit result diverged from the ledger",
                    "fluid=" + fluidId + ", filled=" + filled);
        }
        if (filled < amount && retryAction != null) {
            state.registerRetryAction(requestId, retryAction);
        }
        return filled;
    }

    public static FluidStack drainReserved(
            CompatibilityServerState state,
            UUID requestId,
            String fluidId,
            int amount,
            IFluidHandler handler
    ) {
        return drainReserved(state, requestId, fluidId, amount, handler, null);
    }

    public static FluidStack drainReserved(
            CompatibilityServerState state,
            UUID requestId,
            String fluidId,
            int amount,
            IFluidHandler handler,
            Runnable retryAction
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(handler, "handler");
        if (amount <= 0 || fluidId == null || fluidId.isBlank()) {
            return FluidStack.EMPTY;
        }
        if (!hasReservation(state, requestId, fluidId, amount)) {
            return FluidStack.EMPTY;
        }

        FluidStack available = firstMatchingTank(handler, fluidId, amount);
        if (available.isEmpty() || available.getAmount() < amount) {
            return FluidStack.EMPTY;
        }
        int simulated = handler.drain(available.copyWithAmount(amount), FluidAction.SIMULATE).getAmount();
        if (simulated != amount || !state.beginInFlight(requestId, fluidId, amount)) {
            return FluidStack.EMPTY;
        }

        FluidStack drained;
        try {
            drained = handler.drain(available.copyWithAmount(amount), FluidAction.EXECUTE);
        } catch (RuntimeException error) {
            state.markSuspect(requestId, fluidId,
                    "fluid handler threw during drain; commit result unknown",
                    "fluid=" + fluidId + ", amount=" + amount + ", failure=" + error);
            return FluidStack.EMPTY;
        }
        int drainedAmount = drained.getAmount();
        if (drainedAmount <= 0) {
            // Execute returned nothing: no commit happened, so the request can be retried safely.
            state.scheduleRetry(requestId);
            if (retryAction != null) {
                state.registerRetryAction(requestId, retryAction);
            }
            return FluidStack.EMPTY;
        }
        if (drainedAmount != amount) {
            state.settleInFlight(requestId, fluidId, drainedAmount);
            state.scheduleRetry(requestId);
            if (retryAction != null) {
                state.registerRetryAction(requestId, retryAction);
            }
            return drained;
        }
        if (!state.settleInFlight(requestId, fluidId, amount)) {
            state.markSuspect(requestId, fluidId,
                    "ledger settle failed after fluid drain; commit result diverged from the ledger",
                    "fluid=" + fluidId + ", drained=" + drainedAmount);
        }
        return drained;
    }

    private static boolean hasReservation(
            CompatibilityServerState state,
            UUID requestId,
            String fluidId,
            int amount
    ) {
        return state.reservations().stream().anyMatch(value -> value.requestId().equals(requestId)
                && value.itemId().equals(fluidId)
                && value.amount() == amount
                && value.state() == CompatibilityServerState.ReservationState.RESERVED);
    }

    private static FluidStack firstMatchingTank(IFluidHandler handler, String fluidId, int amount) {
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack stored = handler.getFluidInTank(tank);
            if (stored.isEmpty() || !fluidKey(stored).equals(fluidId)) {
                continue;
            }
            return stored.copyWithAmount(Math.min(stored.getAmount(), amount));
        }
        return FluidStack.EMPTY;
    }

    private static String fluidKey(FluidStack stack) {
        return BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }
}
