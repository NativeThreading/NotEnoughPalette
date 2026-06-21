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

### P1: `toIntIds()` int[4096] allocation
- **Lines**: IndirectStorage:798 / CharGlobalStorage:1001
- **Before**: `new int[entryCount]` allocated per write/pack — 16KB every call
- **After**: `TO_INT_BUF.get()` — shared `ThreadLocal<int[4096]>` buffer
- **Impact**: 384KB/s saved at 24 sections/chunk × 20 TPS auto-save
- **Safety**: `pack()` path (compactGlobal) reads but doesn't store the reference; write() path consumes immediately. Single-threaded server access ensures no reuse conflict.

### P2: `SingleStorage.paletteValues()` double allocation
- **Line**: 527
- **Before**: `new ArrayList<>(List.of(this.value))` — `List.of()` creates immutable list, then ArrayList copies
- **After**: `new ArrayList<>(1); list.add(this.value); return list`
- **Impact**: 1 fewer allocation per uniform-section snapshot

### Helper: `storageLongs(int entryCount, int bits)`
- **Line**: after 1259
- **Formula**: `(entryCount * bits + 63) / 64` — replaces all SimpleBitStorage getRaw().length calls

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

### P2: `pack()` line 220 unnecessary SimpleBitStorage re-wrap
- **Description**: `Optional.of(Arrays.stream(new SimpleBitStorage(packed.bits, ..., packed.ids).getRaw()))` — creates SimpleBitStorage just to pass raw long[] to LongStream
- **Priority**: Low (cold path — chunk serialization only, not hot get/set)
- **Suggested fix**: Use `storageLongs()` to pre-allocate long[] and pack directly, avoiding SimpleBitStorage wrapper

### Warnings (pre-existing)
- Unchecked cast: `R[] results = (R[]) new Object[n]` — standard Java generic array pattern in ParallelWorker clone
- Deprecated API: `builtInRegistryHolder()` in ServerExplosionMixin — Minecraft API version drift

## Performance Context
- NEP reduces chunk memory and CPU by replacing bit-packed `SimpleBitStorage` with direct arrays
- Combined with NT (NativeThreading): baseline v0.1.3 → 161ms MSPT; optimized → 54ms MSPT (rayLookup=false, FORK_JOIN x16)
- Approximate path (rayLookup=true): ~62ms MSPT with cached ray lookup
- The serializedSize and toIntIds optimizations reduce GC pressure on the chunk serialization cold path
