# NotEnoughPalette

Optimized `PalettedContainer` replacement for Minecraft 26.2 — transparently replaces vanilla block and biome storage with O(1) direct-array data structures.

## Features

- **O(1) `get()` / `set()`** — direct `byte[]` / `char[]` / `int[]` arrays, no vanilla `SimpleBitStorage.cellIndex()` bit-packing.
- **Transparent** — extends `PalettedContainer<T>`, Fabric + NeoForge hybrid JAR.
- **Air tracking** — `BitSet airMask` for O(1) air queries.
- **Low memory** — ≤1.5× vanilla heap for typical sections; often _less_.
- **All paths optimized** — factory creation, codec deserialization, network read/write, copy, recreate.

## Installation

Drop `nep-1.0.0.jar` into your `mods/` folder. Requires Fabric Loader ≥ 0.19.3 or NeoForge ≥ 26.2.0.1-beta.

## Performance

Benchmarked with 125 TNT on a 24-core i9-12900HX (Minecraft 26.2, Lithium + NativeThreading):

| Metric | Vanilla | NEP + Lithium + NT |
|--------|---------|---------------------|
| TPS | 13.4 | **18.3** |
| MSPT (mean) | 769 ms | **55 ms** |
| MSPT (95%ile) | 1095 ms | **64 ms** |

## Build

```bash
./gradlew clean assemble          # Fabric + NeoForge hybrid JAR
```

Output: `build/libs/nep-1.0.0.jar`

## License

MIT — see [LICENSE](LICENSE).
