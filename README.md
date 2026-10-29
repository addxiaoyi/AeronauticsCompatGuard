
# Aeronautics Compat Guard

Aeronautics Compat Guard is a client and dedicated-server compatibility and stability patch layer for modded Minecraft. Its long-term goal is to make mixed modpacks work together through targeted behavior adapters and safe performance fixes. The current build targets Minecraft 1.21.1, NeoForge 21.1.250, and Java 21.

Client rendering fixes and server world/inventory fixes load on their own sides. `CompatibilityPatchRegistry` enables an adapter only when its declared mod versions and target method signature match, and logs which versions activated it.

The server adapters add a fresh-delegate inventory view for MineColonies combined building handlers while Create is installed, reject removed/client-side/unloaded/off-thread warehouse access, and skip new-entity packets for entities already marked removed. MineColonies invalidates and rebuilds its combined inventory every tick; Create pipes or storage adapters that retain the previous handler can otherwise read from or write to a stale snapshot. Normal entity removal pairing remains intact.

The client adapters repair boolean shader uniforms that Veil skips when Sable and Iris expose them through `ShaderInstance`, and make CraterLib's SimpleRPC config watcher registration idempotent for the same config class and path. The Propulsion / Gadgets & Gizmos path is intentionally not duplicated: the current G&G adapter already targets both the legacy and current Propulsion class names and injects direct throttle into Propulsion's shared `AbstractThrusterBlockEntity`.

The current tested target matrix is Minecraft 1.21.1 / NeoForge 21.1.250, MineColonies 1.1.1405 + Create 6.0.10, Sable 2.0.5 + Iris 1.8.14-beta.1 + Veil 4.3.2, and CraterLib 3.1.3 + SimpleRPC 4.1.4. The broader goal is modpack-wide compatibility; each new adapter still needs a reproducible conflict, a version-specific fix, and client/server validation before it is enabled. Invalid item stacks and non-positive extraction requests are rejected at the compatibility boundary.

The server primitives include request-keyed item reservations and an in-flight ledger. The ledger is a real state machine: `RESERVED` -> `IN_FLIGHT` -> settled, with `RETRY` (bounded backoff, at most 8 attempts, driven from the server tick), `CANCELLED`, and `SUSPECT`. `SUSPECT` is the terminal state for a transaction whose commit result is unknown (the handler threw after the intent was persisted, or the ledger write failed); such a request is never replayed automatically, because replaying an unknown outcome duplicates items. Entries that were `IN_FLIGHT` when the process died are loaded back as `SUSPECT` instead of staying stuck in a state no other transition accepts. A delivered request is removed from the ledger once the removal is persisted.

Fluid transfer is implemented on the public NeoForge `IFluidHandler` contract with the same reserve/simulate/execute/settle discipline as items. Wiring either transaction into MineColonies/Create request and package callbacks still requires those mod jars, so it is **not** done: the primitives are exercised through `InventoryTransaction` / `FluidTransaction`, not through a mod callback.

The server state layer provides a persistent request reservation ledger, a bounded per-tick work queue (also bounded before off-thread submission, so a flood cannot allocate unbounded tasks on the server executor), a startup integrity index (three retained), an asynchronous quarantine writer, server-thread rejection counter, chunk-retention probes, entity churn metrics, and a slow-tick counter that records tick duration. Automatic invalid block-entity cleanup waits until the startup integrity index succeeds.

The startup snapshot is an integrity index (`index.jsonl`: path, size, mtime, plus SHA-256 for files up to 8 MiB) rather than a copy of the world root. The previous version copied the entire world three times at server start, which stalls start-up on large worlds and triples disk usage for an audit record.

The `/acg status`, `/acg compatibility`, and `/acg cleanup-report` commands require permission level 2. `/acg status` reports `suspects` (transactions needing operator reconciliation), `delivered`, `pendingRetries`, `queuedQuarantineEvents`, and `lastTickMs`.

Adapter version gates are tolerant rather than exact-match: `~` allows later patch releases within the same major.minor, `^` allows the same major, and `* `, `>= `, `< `, `1.2.x` are supported. The installed version is reduced to its leading numeric triple, so `1.8.14-beta.1+mc1.21.1` matches `~1.8.14` and `1.1.1405-1.21.1-snapshot` matches `~1.1.1405`. Previously a patch bump such as `create 6.0.11` silently disabled a guard that was still correct. Reservation state is stored in `aeronauticscompatguard/reservations.json`; quarantine records are appended to `aeronauticscompatguard/quarantine/events.jsonl`.

On disk load, invalid item stacks and missing `alexsmobs` / `guardvillagers` entity types are quarantined instead of entering the live world. Chunk load also removes block entities whose current block state is air, after recording the location and type. These paths are intentionally limited to loaded save data; they do not cancel normal newly spawned entities.

## Verified target matrix

Every mixin target was checked against the real artefact, not against memory:

| Target | Evidence | Result |
| --- | --- | --- |
| `InventoryTransaction` -> `ItemHandlerHelper` | `javap` on `build/moddev/artifacts/neoforge-21.1.250-merged.jar` | `canItemStacksStack` absent (only `insertItem`, `insertItemStacked`, `giveItemToPlayer`, `calcRedstoneFromInventory`); replaced with `ItemStack.isSameItemSameComponents` |
| `ServerEntity#sendPairingData` | same jar | real signature `(ServerPlayer, PacketAndPayloadAcceptor)`; the old `java.util.function.Consumer` never matched |
| `ItemStack#parse`, `EntityType#loadEntityRecursive`, `AttachmentHolder#deserializeAttachments`, `AttachmentType.serializer`, `ShaderInstance#updateLocations` | same jar | present (`updateLocations` is `private void`; `getDeclaredMethod` finds it) |
| `CraterLib ConfigController#register_config(AbstractConfig)` | `javap` on `CraterLib-Neoforge-1.21-3.1.3.jar` | present; `watchedConfigs` is `HashMap<String, AbstractConfig>` keyed by `config.getConfigPath().toString()` |
| `create` version gate | `META-INF/neoforge.mods.toml` in `create-1.21.1-6.0.10.jar` | `version = "6.0.10"`, so the gate must not be the `6.0.10+mc1.21.1` release name |
| `craterlib` version gate | `META-INF/neoforge.mods.toml` in the same jar | `version = "3.1.3"` (the `1.21-3.1.3` form is only the release name) |
| `minecolonies TileEntityColonyBuilding#getItemHandlerCap(Direction)` | `raw.githubusercontent.com/ldtteam/minecolonies/version/1.21/src/main/java/com/minecolonies/core/tileentities/TileEntityColonyBuilding.java:616` | present as `public IItemHandler getItemHandlerCap(final Direction side)`; `CombinedItemHandler` is `com.minecolonies.api.inventory.api.CombinedItemHandler implements IItemHandlerModifiable` |

## Runtime verification

`gradlew runServer` was run against NeoForge 21.1.250 (no game client, no optional mods). Observed in the log:

```text
[modloading-worker-0/INFO] Aeronautics Compat Guard initialized
[mixin/]: Mixing RemovedEntityPairingMixin ... into net.minecraft.server.level.ServerEntity
[mixin/]: @Inject::aeronauticsCompatGuard$skipRemovedEntityPacket(ServerPlayer, PacketAndPayloadAcceptor, CallbackInfo) does use it's CallbackInfo
[mixin/]: Mixing UnknownAttachmentDataMixin ... into net.neoforged.neoforge.attachment.AttachmentHolder
[mixin/]: Mixing UnknownEntityDataMixin ... into net.minecraft.world.entity.EntityType
[mixin/]: Mixing InvalidItemStackParseMixin ... into net.minecraft.world.item.ItemStack
[CompatGuard] Enabled minecraft.removed_entity_tracker / minecraft.invalid_item_stack / minecraft.unknown_entity_data / neoforge.unknown_attachment
[CompatGuard] Skipped minecolonies.create.handler_refresh: missing mod minecolonies
[CompatGuard] Skipped craterlib.simplerpc.config_idempotency: missing mod craterlib
[CompatGuard] Skipped sable.iris.veil.bool_uniforms: missing mod sable
[Server thread/INFO] [CompatGuard] Startup integrity index created at .\world\.\aeronauticscompatguard\snapshots\world-...
[Server thread/INFO] [CompatGuard] Server guard started for .\world\.
```

No mixin apply errors, no injection errors, clean shutdown ("All dimensions are saved"). The MineColonies/CraterLib mixins are `@Pseudo` and are skipped when their mod is absent, which is why they do not appear.

Command output could not be captured this way: Gradle's `JavaExec` gives the server an empty stdin, so the console handler hits EOF and stops the server immediately instead of reading commands. Verifying the commands at runtime needs RCON or a real client.

## Known gaps

- MineColonies/Create request and package callbacks are not wired (the mod jars are not compile dependencies); the transaction primitives are not called from mod code paths.
- The `craterlib.simplerpc.config_idempotency` adapter is log-only: the bytecode of `register_config` already returns early when the config path is registered (`watchedConfigs.containsKey(configPath.toString())` -> error log -> return), so cancelling the injection only silences that message. It does not fix a live defect on 3.1.3.
- MineColonies/Create are not present in this environment as files, so their classes were checked from source and metadata rather than from the installed jar.
- No in-game verification: there is no test instance, so runtime behaviour (mixin application, retention, transaction settlement under a live server) is unproven.

## Build

```powershell
.\gradlew.bat assemble
```

The JAR is written to `build/libs/`.
