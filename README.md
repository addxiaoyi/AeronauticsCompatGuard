
# Aeronautics Compat Guard

NeoForge compatibility diagnostics scaffold for Minecraft 1.21.1 and NeoForge 21.1.250. Java 21 is required.

The common entry point is safe on both physical sides. Client-only code belongs in `client/`; dedicated-server checks belong in `server/`. Compatibility rules should start in report-only mode and become blocking only after the supported mod list has been tested.

## Build

```powershell
.\gradlew.bat assemble
```

The JAR is written to `build/libs/`.
