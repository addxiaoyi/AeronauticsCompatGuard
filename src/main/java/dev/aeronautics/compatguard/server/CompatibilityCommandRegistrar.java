package dev.aeronautics.compatguard.server;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;

public final class CompatibilityCommandRegistrar {
    private CompatibilityCommandRegistrar() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("acg")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("compatibility").executes(context -> compatibility(context.getSource())))
                .then(Commands.literal("cleanup-report").executes(context -> cleanupReport(context.getSource())))
                .then(Commands.literal("selftest").executes(context -> selfTest(context.getSource())))
                .then(Commands.literal("bench").executes(context -> bench(context.getSource())))
                .then(Commands.literal("mixins").executes(context -> mixins(context.getSource())))
                .then(Commands.literal("conflicts").executes(context -> conflicts(context.getSource())))
                .then(Commands.literal("leaks").executes(context -> leaks(context.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        CompatibilityServerState.Status status = state(source).status();
        source.sendSuccess(() -> Component.literal(
                "CompatGuard tick=" + status.tick()
                        + " queue=" + status.queuedWork()
                        + " reservations=" + status.reservations()
                        + " quarantined=" + status.quarantined()
                        + " offThreadRejected=" + status.rejectedOffThread()
                        + " slowTicks=" + status.slowTicks()
                        + " lastTickMs=" + (status.lastTickNanos() / 1_000_000L)
                        + " suspects=" + status.suspects()
                        + " delivered=" + status.delivered()
                        + " pendingRetries=" + status.pendingRetries()
                        + " queuedQuarantineEvents=" + status.queuedQuarantine()
                        + " droppedBlockEntityCleanups=" + status.droppedBlockEntityCleanups()
                        + " trackedUnloadedChunks=" + status.trackedUnloadedChunks()), false);
        return 1;
    }

    private static int compatibility(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Compatibility adapters are version-gated; inspect startup log for enabled/skipped reasons."), false);
        return 1;
    }

    private static int selfTest(CommandSourceStack source) {
        CompatibilityServerState state = state(source);
        InventorySelfTest.Result result = InventorySelfTest.run(state);
        for (String line : result.lines()) {
            source.sendSuccess(() -> Component.literal("[acg selftest] " + line), false);
        }
        return result.ok() ? 1 : 0;
    }

    private static int mixins(CommandSourceStack source) {
        for (String line : dev.aeronautics.compatguard.compat.MixinAudit.report()) {
            source.sendSuccess(() -> Component.literal("[acg mixins] " + line), false);
        }
        return 1;
    }

    private static int leaks(CommandSourceStack source) {
        for (String line : state(source).loggedOutPlayerReport()) {
            source.sendSuccess(() -> Component.literal("[acg leaks] " + line), false);
        }
        return 1;
    }

    private static int conflicts(CommandSourceStack source) {
        for (String line : dev.aeronautics.compatguard.compat.MixinInventory.conflictReport()) {
            source.sendSuccess(() -> Component.literal("[acg conflicts] " + line), false);
        }
        return 1;
    }

    private static int bench(CommandSourceStack source) {
        for (String line : CompatGuardBench.run(state(source))) {
            source.sendSuccess(() -> Component.literal("[acg bench] " + line), false);
        }
        return 1;
    }

    private static int cleanupReport(CommandSourceStack source) {
        CompatibilityServerState.Status status = state(source).status();
        source.sendSuccess(() -> Component.literal(
                "Quarantine events=" + status.quarantined() + "; use the world aeronauticscompatguard/quarantine manifest."), false);
        return 1;
    }

    private static CompatibilityServerState state(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        return CompatibilityServerState.get(server);
    }
}
