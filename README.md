
# Aeronautics Compat Guard

Aeronautics Compat Guard is a client and dedicated-server compatibility and stability patch layer for modded Minecraft. Its long-term goal is to make mixed modpacks work together through targeted behavior adapters and safe performance fixes. The current build targets Minecraft 1.21.1, NeoForge 21.1.250, and Java 21.

Client rendering fixes and server world/inventory fixes load on their own sides. `CompatibilityPatchRegistry` enables an adapter only when its declared mod versions and target method signature match, and logs which versions activated it.

The server adapter adds a fresh-delegate inventory view for MineColonies combined building handlers while Create is installed. MineColonies invalidates and rebuilds that combined inventory every tick; Create pipes or storage adapters that retain the previous handler can otherwise read from or write to a stale snapshot. Each operation resolves the current MineColonies handler and keeps MineColonies' rack insertion/extraction rules in charge. Removed, client-side, unloaded, or off-thread block entities are rejected so a stale server capability cannot mutate an unloaded warehouse object or race the server tick.

The client adapters repair boolean shader uniforms that Veil skips when Sable and Iris expose them through `ShaderInstance`, and make CraterLib's SimpleRPC config watcher registration idempotent for the same config class and path. The Propulsion / Gadgets & Gizmos path is intentionally not duplicated: the current G&G adapter already targets both the legacy and current Propulsion class names and injects direct throttle into Propulsion's shared `AbstractThrusterBlockEntity`.

The current tested target matrix is Minecraft 1.21.1 / NeoForge 21.1.250, MineColonies 1.1.1405 + Create 6.0.10, Sable 2.0.5 + Iris 1.8.14-beta.1 + Veil 4.3.2, and CraterLib 3.1.3 + SimpleRPC 4.1.4. The broader goal is modpack-wide compatibility; each new adapter still needs a reproducible conflict, a version-specific fix, and client/server validation before it is enabled.

The first server fix addresses stale MineColonies item-handler references. It does not yet add a Create-to-colony request bridge, fluid transfer, persistent stock reservations, chunk tickets, or TPS scheduling controls. Those need separate server-side designs and world tests before they should be enabled by default.

## Build

```powershell
.\gradlew.bat assemble
```

The JAR is written to `build/libs/`.
