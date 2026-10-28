
# Aeronautics Compat Guard

NeoForge compatibility diagnostics scaffold for Minecraft 1.21.1 and NeoForge 21.1.250. Java 21 is required.

The common entry point is safe on both physical sides. Client-only code belongs in `client/`; dedicated-server checks belong in `server/`.

The server adapter adds a fresh-delegate inventory view for MineColonies combined building handlers while Create is installed. MineColonies invalidates and rebuilds that combined inventory every tick; Create pipes or storage adapters that retain the previous handler can otherwise read from or write to a stale snapshot. Each operation through this view resolves the current MineColonies handler and keeps MineColonies' rack insertion/extraction rules in charge.

The client adapter repairs boolean shader uniforms that Veil skips when Sable and Iris expose them through `ShaderInstance`. The Propulsion / Gadgets & Gizmos path is intentionally not duplicated: the current G&G adapter already targets both the legacy and current Propulsion class names and injects direct throttle into Propulsion's shared `AbstractThrusterBlockEntity`.

This first server fix addresses stale MineColonies item-handler references. It does not yet add a Create-to-colony request bridge, fluid transfer, persistent stock reservations, chunk tickets, or TPS scheduling controls. Those need separate server-side designs and world tests before they should be enabled by default.

## Build

```powershell
.\gradlew.bat assemble
```

The JAR is written to `build/libs/`.
