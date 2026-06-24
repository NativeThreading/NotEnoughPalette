# NEP (NotEnoughPalette) — Optimization Skill

## Architecture

```
OptimizedPalettedContainer<T> extends PalettedContainer<T>
├── SingleStorage<T>          (uniform sections — single T value)
├── IndirectStorage<T>        (≤256 block types — byte[4096] + Object[] palette)
├── CharGlobalStorage<T>      (>256 types, ≤65535 IDs — char[4096] + Int2IntOpenHashMap counts)
└── IntGlobalStorage<T>       (extreme modpacks — int[4096])
```

Hot path: `get(x,y,z)` → `get(index)` → direct array access (no vanilla `SimpleBitStorage.cellIndex()` bit packing).
Auxiliary: `BitSet airMask` for O(1) air queries (field exists but NEVER READ in hot path).

Build: `./gradlew clean assemble` — produces Fabric + NeoForge hybrid JAR in `build/libs/`.
Source: `common/src/main/java/com/github/uright008/nep/palette/OptimizedPalettedContainer.java` (~1300 lines, single file).

## Applied Optimizations

### P0: `packBits()` direct bit-packing replaces SimpleBitStorage in 5 write/pack sites
- **Sites**: IndirectStorage.write() / IndirectStorage.writeAsGlobal() / CharGlobalStorage.write() / IntGlobalStorage.write() / OptimizedPalettedContainer.pack()
- **Before**: `new SimpleBitStorage(bits, entryCount, ints).getRaw()` — allocated SimpleBitStorage object + internal long[] per section on every chunk save/serialize
- **After**: `packBits(bits, entryCount, ints)` — static method directly bit-packs int[] into newly allocated long[], no wrapper object
- **Impact**: Eliminates ~23,064 SimpleBitStorage object allocations per full save (961 chunks × 24 sections)
- **Method**: `valuesPerLong = 64 / bits; for(i=0; i<entryCount; i++) result[i / valuesPerLong] |= ((long)values[i] & mask) << (i % valuesPerLong) * bits`

### P0: `serializedSize()` throwaway SimpleBitStorage (4 sites)
- **Lines**: IndirectStorage:684,692 / CharGlobalStorage:931 / IntGlobalStorage:1115
- **Before**: `new SimpleBitStorage(bits, entryCount).getRaw().length * Long.BYTES` — allocates full bit-packing object just to compute size
- **After**: `storageLongs(entryCount, bits) * Long.BYTES` — arithmetic: `(entryCount * bits + 63) / 64`
- **Impact**: Eliminates 48-96 allocations per chunk write (2-4 per section × 24 sections per chunk)

### P0: `airMask` dead-weight writes
- **Lines**: `set()`:271, `getAndSet()`:280 — removed `this.setAirMask(index, value)` calls
- **Before**: Every block mutation called `BitSet.set()` on `airMask` — but `airMask` has ZERO reads in the codebase
- **After**: Hot set/getAndSet path skips 2 branches + `BitSet.set()` per call
- **Impact**: ~10% reduction in per-set instruction budget on hot path

### P1: `rebuildAirMask()` SingleStorage short-circuit
- **Lines**: rebuildAirMask()
- **Before**: Full O(entryCount) scan for EVERY section that tracks air — iterates 4096 entries calling valueAt() + isAir() when the section is SingleStorage
- **After**: `instanceof SingleStorage` check → createAirMask(single.value()) directly in O(1)
- **Impact**: Saves 4096 valueAt calls per air-only section during load/read. Most sections after TNT explosion are air.

### P1: `read()` TO_INT_BUF reuse for packed.unpack()
- **Lines**: read() lines 183,192
- **Before**: `int[] ids = new int[this.entryCount]` allocated per section loaded from disk/network (two allocations)
- **After**: `int[] unpackBuf = TO_INT_BUF.get()` — shares existing ThreadLocal buffer
- **Impact**: Eliminates 2 × 4096 int heap allocations per section on chunk load path

### P1: `toIntIds()` int[4096] allocation
- **Lines**: IndirectStorage:798 / CharGlobalStorage:1001
- **Before**: `new int[entryCount]` allocated per write/pack — 16KB every call
- **After**: `TO_INT_BUF.get()` — shared `ThreadLocal<int[4096]>` buffer
- **Impact**: 384KB/s saved at 24 sections/chunk × 20 TPS auto-save
- **Safety**: `pack()` path (compactGlobal) reads but doesn't store the reference; write() path consumes immediately. Single-threaded server access ensures no reuse conflict.

### P1: `IndirectStorage.find()` dead-path elimination
- **Lines**: find()
- **Before**: Two-path logic — reverse map getInt() + linear scan fallback for indices 0-15 with cache-fill into reverse. The linear scan was dead code because reverse map (initialized at size=16) contains ALL entries 0+.
- **After**: Single path — `if (reverse != null) return reverse.getInt(value)` else linear scan `for(i=0; i<size; i++)`
- **Impact**: Removes dead code path + unnecessary reverse.put cache-fill. `getEntryAfterMiss` profile nodes reduced (18→13 observed).

### P1: `addPaletteEntry()` prevent reverse map replacement
- **Lines**: addPaletteEntry() transition at SMALL_PALETTE_SIZE
- **Before**: `if (id == SMALL_PALETTE_SIZE)` always created a NEW reverse map, even when constructor had already pre-created one (palettes > 16 entries). Threw away accumulated entries and re-inserted.
- **After**: `if (this.reverse == null && id == SMALL_PALETTE_SIZE)` — only creates reverse map when not already present
- **Impact**: Avoids redundant hash map creation + re-insertion of 17 entries during construction of palettes > 16

### P2: `SingleStorage.paletteValues()` double allocation
- **Line**: 527
- **Before**: `new ArrayList<>(List.of(this.value))` — `List.of()` creates immutable list, then ArrayList copies
- **After**: `new ArrayList<>(1); list.add(this.value); return list`
- **Impact**: 1 fewer allocation per uniform-section snapshot

### P2: Removed unused `IndirectStorage.entry()` method
- **Lines**: IndirectStorage:810-813 (removed)
- **Before**: `private T entry(int id) { return (T)this.palette[id]; }` — all 11 callers inlined in commit 5026646, method became dead code
- **After**: Removed entirely
- **Impact**: Cleaner code, -5 lines

### Helper: `storageLongs(int entryCount, int bits)`
- **Formula**: `(entryCount * bits + 63) / 64` — replaces all SimpleBitStorage getRaw().length calls

### Helper: `packBits(int bits, int entryCount, int[] values)`
- **Signature**: `private static long[] packBits(int bits, int entryCount, int[] values)`
- **Behavior**: Directly bit-packs int[] into long[]. Replaces new SimpleBitStorage() wrapper in all 5 write/serialize sites.

### Public API: `isUniformAir()`
- **Signature**: `public boolean isUniformAir()`
- **Behavior**: Returns true iff container is `tracksAir && storage instanceof SingleStorage && isAir(value)`. Enables external callers (mixins, other mods) to short-circuit block state reads for air-only sections.

## Development Guide

```bash
# Build
./gradlew clean assemble

# Deploy to test server
cp build/libs/notenoughpalette-*.jar ~/fabric-server/mods/

# Run benchmark (from fabric-server)
python quick-test.py --combo nep+native --warmup 20 --duration 25

# Analyze results
python analyze-spark.py bench-results/<latest>/nep_native-run1-profile-*.sparkprofile
```

## Remaining Issues

### Resolved
- pack() SimpleBitStorage re-wrap — fixed by `packBits()` method, all 5 write/pack sites bypass SimpleBitStorage

### Open
- `read()` still uses SimpleBitStorage for UNPACKING (receiving bit-packed data from wire/disk). This is unavoidable without reimplementing bit-unpacking. Acceptable because chunk loading is cold path.
- NEP constructor calls `super()` which allocates vanilla `PalettedContainer.Data` that is never used (~100 bytes per container). ~2.3MB waste for 23K sections at world load. Acceptable.
- The hot-path `get()` is fully inlined — 0 profile nodes. Further optimization requires reducing the NUMBER of `getBlockState()` calls, not per-call cost.

### Future Exploration
- **LevelChunkSection air short-circuit**: A mixin intercepting `LevelChunkSection.getBlockState()` to return AIR immediately when `states` is UniformAir NEP container. Added and removed in this cycle — adds instanceof + isUniformAir() overhead to every getBlockState call; benefit depends on air-to-nonair section ratio. Revisit if benchmark consistently shows >60% air sections.
- **IndirectStorage.count() bounds check**: The `if (id < localSize)` check on line ~646 is theoretically unnecessary for well-formed data. Removing it risks undefined behavior on corrupted data. Deemed not worth the risk.
- **LevelChunkSection.hasOnlyAir() fast path**: NEP could maintain the `nonEmptyBlockCount` field alongside the airMask, enabling vanilla's existing empty-section fast path.

## Performance Context
- NEP reduces chunk memory and CPU by replacing bit-packed `SimpleBitStorage` with direct arrays
- Combined with NT (NativeThreading): baseline v0.1.3 → 161ms MSPT; optimized → 54ms MSPT (rayLookup=false, FORK_JOIN x16)
- Approximate path (rayLookup=true): ~62ms MSPT with cached ray lookup
- The serializedSize and toIntIds optimizations reduce GC pressure on the chunk serialization cold path
