# NotEnoughPalette (NEP)

Optimized `PalettedContainer` replacement for Minecraft 26.2, transparently replacing vanilla block/biome storage with O(1) direct-array data structures.

## Build

```bash
./gradlew clean assemble          # Fabric + NeoForge hybrid JAR
./gradlew :fabric:compileJava     # Fabric only
./gradlew :neoforge:compileJava   # NeoForge only
```

Output: `build/libs/nep-1.0.0.jar` (hybrid, includes both loader manifests)

## Project Structure

```
common/src/main/java/com/github/uright008/nep/
  palette/
    OptimizedPalettedContainer.java   # Core optimization (1300+ lines)
  mixin/
    PalettedContainerFactoryMixin.java # Redirects createForBlockStates/createForBiomes
    PalettedContainerMixin.java        # Redirects static PalettedContainer.unpack()
common/src/main/resources/
  notenoughpalette.mixins.json         # Mixin config (2 mixins)
fabric/src/main/resources/
  fabric.mod.json                      # Fabric mod manifest
neoforge/src/main/resources/META-INF/
  neoforge.mods.toml                   # NeoForge mod manifest
```

## Architecture

`OptimizedPalettedContainer<T>` extends vanilla `PalettedContainer<T>` and replaces all internal storage with tagged direct arrays:

| Mode | Storage | Use |
|------|---------|-----|
| `SingleStorage` | single `T` value | uniform sections (all air) |
| `IndirectStorage` | `byte[4096]` + `Object[]` palette (≤256 types) | typical sections |
| `CharGlobalStorage` | `char[4096]` global IDs | diverse sections (>256 types, ≤65535 IDs) |
| `IntGlobalStorage` | `int[4096]` global IDs | extreme modpacks (>65535 unique blocks) |

Hot path: `get(x,y,z)` → `get(index)` → direct array access (no vanilla `SimpleBitStorage.cellIndex()` bit packing).

Auxiliary: `BitSet airMask` for O(1) air queries, `Int2IntOpenHashMap counts` for O(paletteSize) iteration.

## Commit Convention

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

perf(palette): inline IndirectStorage.entry() into valueAt
feat(mixin): redirect PalettedContainerFactory to optimized container
fix(palette): protected get(int) override for UpgradeData path
chore(build): update Gradle wrapper
```

Scopes: `palette`, `mixin`, `build`, `docs`

## Testing

Benchmark script: `~/fabric-server/quick-test.py`
```bash
cd ~/fabric-server && python quick-test.py --combo nep+native --duration 60
```
Uses spark profiler via RCON. Results in `~/fabric-server/bench-results/`.
