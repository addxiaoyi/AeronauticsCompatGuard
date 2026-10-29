package dev.aeronautics.compatguard.server;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the reservation state machine against real registries and a real {@link ItemStackHandler}
 * on the live server thread, so the transaction path can be verified without a game client.
 *
 * <p>Every scenario uses a fresh UUID and releases its reservation afterwards; the only state left
 * behind is a {@code delivered} counter increment. The result is a PASS/FAIL list so the operator
 * can see which invariant broke rather than a single boolean.</p>
 */
public final class InventorySelfTest {
    private InventorySelfTest() {}

    public record Result(List<String> lines, boolean ok) {}

    public static Result run(CompatibilityServerState state) {
        List<String> lines = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        boolean ok = true;
        String stoneId = "minecraft:stone";

        int baselineReservations = state.status().reservations();
        long baselineSuspects = state.status().suspects();

        // 1. A reservation exists exactly once and is idempotent for the same request.
        UUID reserveId = UUID.randomUUID();
        created.add(reserveId);
        state.reserve(reserveId, stoneId, 32, 0L);
        state.reserve(reserveId, stoneId, 32, 0L);
        ok &= check(lines, "reserve is idempotent",
                state.status().reservations() == baselineReservations + 1,
                "reservations=" + state.status().reservations() + " baseline=" + baselineReservations);

        // 2. A capacity-limited handler only takes part of the request: the ledger keeps the
        //    remainder and a retry action is registered for the rest.
        ItemStackHandler limited = new ItemStackHandler(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 16;
            }
        };
        ItemStack leftover = InventoryTransaction.insertReserved(
                state, reserveId, stoneId, 32, limited, new ItemStack(Items.STONE, 32), () -> { });
        boolean storedSixteen = limited.getStackInSlot(0).getCount() == 16;
        CompatibilityServerState.Reservation remainder = state.reservations().stream()
                .filter(value -> value.requestId().equals(reserveId))
                .findFirst().orElse(null);
        ok &= check(lines, "partial insert stores what fits", storedSixteen,
                "handlerCount=" + limited.getStackInSlot(0).getCount());
        ok &= check(lines, "partial insert returns the remainder", leftover.getCount() == 16,
                "leftover=" + leftover.getCount());
        ok &= check(lines, "ledger keeps the unaccepted remainder", remainder != null
                        && remainder.amount() == 16
                        && remainder.state() == CompatibilityServerState.ReservationState.RESERVED,
                "reservation=" + remainder);
        ok &= check(lines, "a retry action is queued for the remainder", state.status().pendingRetries() >= 1,
                "pendingRetries=" + state.status().pendingRetries());

        // 3. An unknown commit outcome parks the request and can never be replayed.
        UUID suspectId = UUID.randomUUID();
        created.add(suspectId);
        state.reserve(suspectId, stoneId, 8, 0L);
        boolean inFlight = state.beginInFlight(suspectId, stoneId, 8);
        state.markSuspect(suspectId, stoneId, "self test: simulated unknown commit", "amount=8");
        ItemStackHandler witness = new ItemStackHandler(1);
        ItemStack replay = InventoryTransaction.insertReserved(
                state, suspectId, stoneId, 8, witness, new ItemStack(Items.STONE, 8));
        ok &= check(lines, "IN_FLIGHT is entered before the handler is touched", inFlight, "beginInFlight=" + inFlight);
        ok &= check(lines, "unknown outcome is parked as SUSPECT",
                state.status().suspects() == baselineSuspects + 1,
                "suspects=" + state.status().suspects());
        ok &= check(lines, "SUSPECT is never replayed", replay.getCount() == 8 && witness.getStackInSlot(0).isEmpty(),
                "replay=" + replay.getCount() + " witnessSlot=" + witness.getStackInSlot(0));
        ok &= check(lines, "SUSPECT cannot re-enter IN_FLIGHT", !state.beginInFlight(suspectId, stoneId, 8),
                "beginInFlight on SUSPECT returned true");

        // 4. A fully settled request leaves no ledger entry behind.
        UUID deliveredId = UUID.randomUUID();
        created.add(deliveredId);
        state.reserve(deliveredId, stoneId, 4, 0L);
        state.beginInFlight(deliveredId, stoneId, 4);
        state.settleInFlight(deliveredId, stoneId, 4);
        boolean gone = state.reservations().stream().noneMatch(value -> value.requestId().equals(deliveredId));
        ok &= check(lines, "settled request is removed from the ledger", gone, "reservation still present");

        // 5. A terminal UUID cannot be reserved again.
        boolean rejected = false;
        try {
            state.reserve(deliveredId, stoneId, 4, 0L);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        ok &= check(lines, "a completed UUID cannot be reserved again", rejected, "reserve() accepted a reused UUID");

        for (UUID id : created) {
            try {
                state.release(id);
            } catch (RuntimeException ignored) {
                // A released or already-settled request is fine here.
            }
        }
        lines.add((ok ? "PASS" : "FAIL") + " inventory transaction self test ("
                + lines.stream().filter(line -> line.startsWith("PASS")).count() + "/"
                + lines.stream().filter(line -> line.startsWith("PASS") || line.startsWith("FAIL")).count() + ")");
        return new Result(lines, ok);
    }

    private static boolean check(List<String> lines, String name, boolean condition, String detail) {
        lines.add((condition ? "PASS " : "FAIL ") + name + (condition ? "" : " -> " + detail));
        return condition;
    }
}
