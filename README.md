
# Aeronautics Compat Guard

NeoForge compatibility diagnostics scaffold for Minecraft 1.21.1 and NeoForge 21.1.250. Java 21 is required.

The common entry point is safe on both physical sides. Client-only code belongs in `client/`; dedicated-server checks belong in `server/`. `CompatibilityPatchRegistry` enables an adapter only when its declared mod versions and target method signature match, and logs which versions activated it.

The server adapter adds a fresh-delegate inventory view for MineColonies combined building handlers while Create is installed. MineColonies invalidates and rebuilds that combined inventory every tick; Create pipes or storage adapters that retain the previous handler can otherwise read from or write to a stale snapshot. Each operation resolves the current MineColonies handler and keeps MineColonies' rack insertion/extraction rules in charge. Removed, client-side, or unloaded block entities are rejected so a stale server capability cannot mutate an unloaded warehouse object.

The client adapter repairs boolean shader uniforms that Veil skips when Sable and Iris expose them through `ShaderInstance`. The Propulsion / Gadgets & Gizmos path is intentionally not duplicated: the current G&G adapter already targets both the legacy and current Propulsion class names and injects direct throttle into Propulsion's shared `AbstractThrusterBlockEntity`.

The initial tested matrix is Minecraft 1.21.1 / NeoForge 21.1.250, MineColonies 1.1.1405 + Create 6.0.10, and Sable 2.0.5 + Iris 1.8.14-beta.1 + Veil 4.3.2. Other versions keep the adapters disabled until their API shape and behavior are checked.

The first server fix addresses stale MineColonies item-handler references. It does not yet add a Create-to-colony request bridge, fluid transfer, persistent stock reservations, chunk tickets, or TPS scheduling controls. Those need separate server-side designs and world tests before they should be enabled by default.

## Build

```powershell
.\gradlew.bat assemble
```

The JAR is written to `build/libs/`.
