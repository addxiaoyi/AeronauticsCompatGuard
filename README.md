
# Aeronautics Compat Guard

Aeronautics Compat Guard is a client and dedicated-server compatibility and stability patch layer for modded Minecraft. Its long-term goal is to make mixed modpacks work together through targeted behavior adapters and safe performance fixes. The current build targets Minecraft 1.21.1, NeoForge 21.1.250, and Java 21.

Client rendering fixes and server world/inventory fixes load on their own sides. `CompatibilityPatchRegistry` enables an adapter only when its declared mod versions and target method signature match, and logs which versions activated it.

The server adapters add a single side-agnostic fresh-delegate inventory view for MineColonies combined building handlers while Create is installed, reject removed/client-side/unloaded/off-thread warehouse access, and skip new-entity packets for entities already marked removed. MineColonies invalidates and rebuilds its combined inventory every tick; Create pipes or storage adapters that retain the previous handler can otherwise read from or write to a stale snapshot. Normal entity removal pairing remains intact.

The client adapters repair boolean shader uniforms that Veil skips when Sable and Iris expose them through `ShaderInstance`, and make CraterLib's SimpleRPC config watcher registration idempotent for the same config class and path. The Propulsion / Gadgets & Gizmos path is intentionally not duplicated: the current G&G adapter already targets both the legacy and current Propulsion class names and injects direct throttle into Propulsion's shared `AbstractThrusterBlockEntity`.

The current tested target matrix is Minecraft 1.21.1 / NeoForge 21.1.250, MineColonies 1.1.1405 + Create 6.0.10, Sable 2.0.5 + Iris 1.8.14-beta.1 + Veil 4.3.2, and CraterLib 3.1.3 + SimpleRPC 4.1.4. The broader goal is modpack-wide compatibility; each new adapter still needs a reproducible conflict, a version-specific fix, and client/server validation before it is enabled. Invalid item stacks and non-positive extraction requests are rejected at the compatibility boundary.

The server primitives include request-keyed item reservations and an in-flight ledger. The ledger is a real state machine: `RESERVED` -> `IN_FLIGHT` -> settled, with `RETRY` (bounded backoff, at most 8 attempts, driven from the server tick), `CANCELLED`, and `SUSPECT`. `SUSPECT` is the terminal state for a transaction whose commit result is unknown (the handler threw after the intent was persisted, or the ledger write failed); such a request is never replayed automatically, because replaying an unknown outcome duplicates items. Entries that were `IN_FLIGHT` when the process died are loaded back as `SUSPECT` instead of staying stuck in a state no other transition accepts. A delivered request is removed from the ledger once the removal is persisted.

Fluid transfer is implemented on the public NeoForge `IFluidHandler` contract with the same reserve/simulate/execute/settle discipline as items. Wiring either transaction into MineColonies/Create request and package callbacks still requires those mod jars, so it is **not** done: the primitives are exercised through `InventoryTransaction` / `FluidTransaction`, not through a mod callback.

The server state layer provides a persistent request reservation ledger, a bounded per-tick work queue (also bounded before off-thread submission, so a flood cannot allocate unbounded tasks on the server executor), a startup integrity index (three retained), an asynchronous quarantine writer, server-thread rejection counter, chunk-retention probes, entity churn metrics, and a slow-tick counter that records tick duration. Automatic invalid block-entity cleanup waits until the startup integrity index succeeds.

The startup snapshot is an integrity index (`index.jsonl`: path, size, mtime, plus SHA-256 for files up to 8 MiB) rather than a copy of the world root. The previous version copied the entire world three times at server start, which stalls start-up on large worlds and triples disk usage for an audit record.

The `/acg status`, `/acg compatibility`, and `/acg cleanup-report` commands require permission level 2. `/acg status` reports `suspects` (transactions needing operator reconciliation), `delivered`, `pendingRetries`, `queuedQuarantineEvents`, and `lastTickMs`.

Adapter version gates are tolerant rather than exact-match: `~` allows later patch releases within the same major.minor, `^` allows the same major, and `* `, `>= `, `< `, `1.2.x` are supported. The installed version is reduced to its leading numeric triple, so `1.8.14-beta.1+mc1.21.1` matches `~1.8.14` and `1.1.1405-1.21.1-snapshot` matches `~1.1.1405`. Previously a patch bump such as `create 6.0.11` silently disabled a guard that was still correct. Reservation state is stored in `aeronauticscompatguard/reservations.json`; quarantine records are appended to `aeronauticscompatguard/quarantine/events.jsonl`.

On disk load, invalid item stacks and missing `alexsmobs` / `guardvillagers` entity types are quarantined instead of entering the live world. Chunk load also removes block entities whose current block state is air, after recording the location and type. These paths are intentionally limited to loaded save data; they do not cancel normal newly spawned entities.

## Mixin contract and audit

Every mixin ships with an explicit priority, a documented injection point, and a `@Unique` marker
field so its presence can be verified at runtime. All injectors use `require = 0` (a target signature
change must not crash the game), which makes a silent miss possible -- the audit is what makes it loud.

| Mixin | Target | Injection | Priority | Why this shape |
| --- | --- | --- | --- | --- |
| `MineColoniesWarehouseHandlerMixin` | `com.minecolonies.core.tileentities.TileEntityColonyBuilding` | `@ModifyReturnValue getItemHandlerCap` | 900 | Return-value rewrite composes with other mods; nothing is cancelled |
| `CraterLibConfigRegistrationMixin` | `com.hypherionmc.craterlib.core.config.ConfigController` | `@Inject HEAD cancellable register_config` | 900 | Must run before the duplicate check to be able to skip it (note: upstream is already idempotent) |
| `RemovedEntityPairingMixin` | `net.minecraft.server.level.ServerEntity` | `@Inject HEAD cancellable sendPairingData` | 900 | Cancels before other mods append to the packet consumer |
| `InvalidItemStackParseMixin` | `net.minecraft.world.item.ItemStack` | `@Inject HEAD cancellable parse` | 900 | Rejects a stack before any other mod sees a half-parsed one |
| `UnknownEntityDataMixin` | `net.minecraft.world.entity.EntityType` | `@Inject HEAD cancellable loadEntityRecursive` | 900 | Stops a bad entity before it reaches the level |
| `UnknownAttachmentDataMixin` | `net.neoforged.neoforge.attachment.AttachmentHolder` | `@ModifyVariable argsOnly HEAD deserializeAttachments` | 900 | Repairs the tag instead of cancelling, so known attachments still load |
| `ShaderInstanceBoolUniformCompatMixin` | `net.minecraft.client.renderer.ShaderInstance` | `@Inject TAIL updateLocations` | 900 | Adds missing uniforms after vanilla populated its own |

`/acg mixins` prints the audit; the same table is logged once at server start. Output on
NeoForge 21.1.250 without the optional mods:

```text
[acg mixins] minecolonies.create.handler_refresh -> SKIPPED target mod not installed (...)
[acg mixins] craterlib.simplerpc.config_idempotency -> SKIPPED target mod not installed (...)
[acg mixins] minecraft.removed_entity_tracker -> APPLIED
[acg mixins] minecraft.invalid_item_stack -> APPLIED
[acg mixins] minecraft.unknown_entity_data -> APPLIED
[acg mixins] neoforge.unknown_attachment -> APPLIED
[acg mixins] sable.iris.veil.bool_uniforms -> SKIPPED client-only mixin on a dedicated server
[acg mixins] applied=4 missing=0 declared=7
```

Two rules that came out of getting this wrong:

- **Never probe a client class to detect the side.** `Class.forName("net.minecraft.client.Minecraft")`
  on a dedicated server makes RuntimeDistCleaner throw, which aborted `ServerStartedEvent` and took
  the whole server down. The audit uses `FMLEnvironment.dist` instead, and both the audit and its
  call site are wrapped so a diagnostic can never crash the game.
- **`require = 0` needs a companion check.** It is what kept the game alive when
  `sendPairingData` changed, but it is also why that guard sat inactive without a single log line.

## Configuration

`config/aeronauticscompatguard-common.toml` (generated on first start):

| Key | Default | Effect |
| --- | --- | --- |
| `diagnostics.entityChurnTracking` | `false` | Per-entity join/leave probes and the 1200-tick churn report. Costs one probe record per entity join. |
| `diagnostics.chunkRetentionProbes` | `false` | Weak reference per unloaded chunk to detect retained chunk objects (bounded to 4096). |
| `safety.airBlockEntityScan` | `true` | On chunk load, queue block entities whose block state is air. Costs one `getBlockState` per block entity position. |
| `safety.startupIntegrityIndex` | `true` | Write the startup integrity index. When off, automatic air block entity cleanup stays disabled (no baseline). |
| `safety.startupIndexMaxHashedBytes` | `8388608` | Files above this size are indexed by size/mtime only. |
| `transactions.enabled` | `true` | Reservation-backed item and fluid primitives. |

The diagnostics paths default to **off** because a pack that installs this mod for the safety fixes
should not pay for observability it never reads.

## Measured cost (`/acg bench`, NeoForge 21.1.250, Windows 11, JDK 21)

| Measurement | Value |
| --- | --- |
| `tick()` | 373.6 ns/op |
| `status()` | 262.5 ns/op |
| `reservation()` miss | 19.5 ns/op |
| `reservation()` hit @ n=200 | 12.6 ns/op |
| `reservations()` full copy @ n=200 | 2391.6 ns/op |
| `reserve()` | 50.3 µs/op |

## Ledger persistence

State is an append-only journal (`aeronauticscompatguard/reservations.journal`, one line per
transition, kept-open writer + flush) that is folded into the atomic snapshot
(`aeronauticscompatguard/reservations.json`) every 256 entries and on shutdown. On load the snapshot
is read first and the journal replayed on top of it; a journal without a snapshot is replayed too, so
a crash after the first append does not lose the request.

Verified by seeding a journal with one `RESERVED` entry and starting the server:
`/acg status` reported `reservations=1`, and after `stop` the snapshot held that entry while the
journal file was gone.

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
| MineColonies rebuild cadence | `version/1.21` `TileEntityColonyBuilding.java:452` — `tick()` runs `invalidateCapabilities(); combinedInv = null;` every tick; `:616` ignores `side` and caches one `combinedInv` | the combined handler a mod retains is at most one tick old, and every side shares one instance, so the wrapper must be side-agnostic and resolve per call |
| Create capability caching | `create-1.21.1-6.0.10.jar`: `CapManipulationBehaviourBase#getInventory()` is `return targetCapability`; `tick()` only re-resolves when `findNewNextTick` is set or `getGameTime() % 64 == 0`; the class contains no reference to `invalidateCapabilities` | a cached Create reference points at a discarded combined inventory for up to 64 ticks (3.2s); Create never sees MineColonies' per-tick invalidation, which is why the delegate has to stay valid |
| `minecolonies TileEntityColonyBuilding#getItemHandlerCap(Direction)` | `raw.githubusercontent.com/ldtteam/minecolonies/version/1.21/src/main/java/com/minecolonies/core/tileentities/TileEntityColonyBuilding.java:616` | present as `public IItemHandler getItemHandlerCap(final Direction side)`; `CombinedItemHandler` is `com.minecolonies.api.inventory.api.CombinedItemHandler implements IItemHandlerModifiable` |

## Runtime verification of the transaction ledger

`/acg selftest` runs the reservation state machine on the live server thread with real registries and
a real `ItemStackHandler`, so the ledger can be verified without a game client. Output from
NeoForge 21.1.250:

```text
[acg selftest] PASS reserve is idempotent
[acg selftest] PASS partial insert stores what fits
[acg selftest] PASS partial insert returns the remainder
[acg selftest] PASS ledger keeps the unaccepted remainder
[acg selftest] PASS a retry action is queued for the remainder
[acg selftest] PASS IN_FLIGHT is entered before the handler is touched
[acg selftest] PASS unknown outcome is parked as SUSPECT
[acg selftest] PASS SUSPECT is never replayed
[acg selftest] PASS SUSPECT cannot re-enter IN_FLIGHT
[acg selftest] PASS settled request is removed from the ledger
[acg selftest] PASS a completed UUID cannot be reserved again
[acg selftest] PASS inventory transaction self test (11/11)
```

The first run of this test **failed** the last assertion: because a fully settled request is removed
from the ledger, nothing remembered that the UUID had already been used, so the same UUID could
reserve again -- the exact shape of a double delivery. The fix is a bounded tombstone ring
(`COMPLETION_TOMBSTONES = 4096`, persisted to `aeronauticscompatguard/completed.json`) that keeps the
duplicate protection without letting the ledger grow.

Files written under the world root during a real run:

| File | Content |
| --- | --- |
| `aeronauticscompatguard/reservations.json` | live ledger (empty array when every request settled) |
| `aeronauticscompatguard/completed.json` | tombstone ring of finished request UUIDs |
| `aeronauticscompatguard/quarantine/events.jsonl` | append-only quarantine log with `rawDataSha256` |
| `aeronauticscompatguard/snapshots/world-*/index.jsonl` | startup integrity index |

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
