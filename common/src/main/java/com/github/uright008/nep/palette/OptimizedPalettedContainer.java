package com.github.uright008.nep.palette;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.DataResult;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.block.state.BlockState;

public final class OptimizedPalettedContainer<T> extends PalettedContainer<T> {
    private static final int SMALL_PALETTE_SIZE = 16;
    private static final int MAX_BYTE_PALETTE_SIZE = 256;
    private static final int GLOBAL_COUNT_INDEX_LIMIT = 1024;

    private final T defaultValue;
    private final Strategy<T> strategy;
    private final int entryCount;
    private final int maxIndirectBits;
    private final boolean tracksAir;
    private final ThreadingDetector threadingDetector = new ThreadingDetector("OptimizedPalettedContainer");
    private volatile Storage<T> storage;
    private BitSet airMask;

    public OptimizedPalettedContainer(final T defaultValue, final Strategy<T> strategy) {
        super(defaultValue, strategy);
        this.defaultValue = defaultValue;
        this.strategy = strategy;
        this.entryCount = strategy.entryCount();
        this.maxIndirectBits = this.entryCount == 4096 ? 8 : 3;
        this.tracksAir = defaultValue instanceof BlockState;
        this.storage = new SingleStorage<>(defaultValue);
        this.airMask = this.createAirMask(defaultValue);
    }

    private OptimizedPalettedContainer(final T defaultValue, final Strategy<T> strategy, final Storage<T> storage, final BitSet airMask) {
        super(defaultValue, strategy);
        this.defaultValue = defaultValue;
        this.strategy = strategy;
        this.entryCount = strategy.entryCount();
        this.maxIndirectBits = this.entryCount == 4096 ? 8 : 3;
        this.tracksAir = defaultValue instanceof BlockState;
        this.storage = storage;
        this.airMask = airMask == null ? null : (BitSet)airMask.clone();
    }

    public static <T> DataResult<PalettedContainer<T>> unpack(final Strategy<T> strategy, final PalettedContainerRO.PackedData<T> packedData) {
        List<T> palette = packedData.paletteEntries();
        if (palette.isEmpty()) {
            return DataResult.error(() -> "Missing palette entries for optimized PalettedContainer");
        }

        try {
            OptimizedPalettedContainer<T> container = new OptimizedPalettedContainer<>(palette.getFirst(), strategy);
            Optional<LongStream> rawStorage = packedData.storage();
            if (rawStorage.isEmpty()) {
                if (packedData.bitsPerEntry() != PalettedContainerRO.PackedData.UNKNOWN_BITS_PER_ENTRY && packedData.bitsPerEntry() != 0) {
                    return DataResult.error(() -> "Invalid bit count, calculated 0, but container declared " + packedData.bitsPerEntry());
                }

                container.storage = new SingleStorage<>(palette.getFirst());
                container.rebuildAirMask();
                return DataResult.success(container);
            }

            long[] raw = rawStorage.get().toArray();
            int bits = packedData.bitsPerEntry() == PalettedContainerRO.PackedData.UNKNOWN_BITS_PER_ENTRY
                ? inferStorageBits(strategy.entryCount(), raw.length, palette.size())
                : packedData.bitsPerEntry();
            int expectedBits = packedStorageBits(strategy.entryCount(), palette.size());
            if (packedData.bitsPerEntry() != PalettedContainerRO.PackedData.UNKNOWN_BITS_PER_ENTRY && expectedBits != packedData.bitsPerEntry()) {
                return DataResult.error(() -> "Invalid bit count, calculated " + expectedBits + ", but container declared " + packedData.bitsPerEntry());
            }

            SimpleBitStorage packed = new SimpleBitStorage(bits, strategy.entryCount(), raw);
            int[] ids = new int[strategy.entryCount()];
            packed.unpack(ids);
            container.storage = container.storageFromLocalIds(palette, ids);
            container.rebuildAirMask();
            return DataResult.success(container);
        } catch (RuntimeException exception) {
            return DataResult.error(() -> "Failed to read optimized PalettedContainer: " + exception.getMessage());
        }
    }

    @Override
    public void acquire() {
        this.threadingDetector.checkAndLock();
    }

    @Override
    public void release() {
        this.threadingDetector.checkAndUnlock();
    }

    @Override
    public int onResize(final int bits, final T lastAddedValue) {
        Storage<T> old = this.storage;
        Storage<T> next = this.growFor(lastAddedValue);
        this.storage = next;
        return next.idForExisting(lastAddedValue);
    }

    @Override
    public T getAndSet(final int x, final int y, final int z, final T value) {
        this.acquire();

        try {
            return this.getAndSet(this.strategy.getIndex(x, y, z), value);
        } finally {
            this.release();
        }
    }

    @Override
    public T getAndSetUnchecked(final int x, final int y, final int z, final T value) {
        return this.getAndSet(this.strategy.getIndex(x, y, z), value);
    }

    @Override
    public void set(final int x, final int y, final int z, final T value) {
        this.acquire();

        try {
            this.set(this.strategy.getIndex(x, y, z), value);
        } finally {
            this.release();
        }
    }

    @Override
    public T get(final int x, final int y, final int z) {
        return this.get(this.strategy.getIndex(x, y, z));
    }

    @Override
    protected T get(final int index) {
        return this.storage.valueAt(index, this.strategy.globalMap());
    }

    @Override
    public void getAll(final Consumer<T> consumer) {
        this.storage.getAll(consumer, this.strategy.globalMap());
    }

    @Override
    public void read(final FriendlyByteBuf buffer) {
        this.acquire();

        try {
            int bits = buffer.readByte();
            if (bits == 0) {
                this.storage = new SingleStorage<>(this.strategy.globalMap().byIdOrThrow(buffer.readVarInt()));
                this.rebuildAirMask();
                return;
            }

            if (bits <= this.maxIndirectBits) {
                int paletteSize = buffer.readVarInt();
                List<T> palette = new ArrayList<>(paletteSize);
                for (int i = 0; i < paletteSize; i++) {
                    palette.add(this.strategy.globalMap().byIdOrThrow(buffer.readVarInt()));
                }

                SimpleBitStorage packed = new SimpleBitStorage(bits, this.entryCount);
                buffer.readFixedSizeLongArray(packed.getRaw());
                int[] ids = new int[this.entryCount];
                packed.unpack(ids);
                this.storage = this.storageFromLocalIds(palette, ids);
                this.rebuildAirMask();
                return;
            }

            SimpleBitStorage packed = new SimpleBitStorage(storageBitsForWire(bits, this.strategy), this.entryCount);
            buffer.readFixedSizeLongArray(packed.getRaw());
            int[] globalIds = new int[this.entryCount];
            packed.unpack(globalIds);
            this.storage = this.globalStorageFromIds(globalIds);
            this.rebuildAirMask();
        } finally {
            this.release();
        }
    }

    @Override
    public void write(final FriendlyByteBuf buffer) {
        this.acquire();

        try {
            Storage<T> snapshot = this.storage;
            snapshot.write(buffer, this.strategy.globalMap(), this.entryCount, this.maxIndirectBits);
        } finally {
            this.release();
        }
    }

    @Override
    public PalettedContainerRO.PackedData<T> pack(final Strategy<T> strategy) {
        this.acquire();

        try {
            Packed<T> packed = this.storage.pack(strategy.globalMap(), strategy.entryCount());
            Optional<LongStream> values = Optional.empty();
            if (packed.bits != 0) {
                values = Optional.of(Arrays.stream(new SimpleBitStorage(packed.bits, strategy.entryCount(), packed.ids).getRaw()));
            }

            return new PalettedContainerRO.PackedData<>(packed.palette, values, packed.bits);
        } finally {
            this.release();
        }
    }

    @Override
    public int getSerializedSize() {
        return this.storage.serializedSize(this.strategy.globalMap(), this.entryCount, this.maxIndirectBits);
    }

    @Override
    @VisibleForTesting
    public int bitsPerEntry() {
        return this.storage.bits(this.entryCount, this.maxIndirectBits, this.strategy.globalMap());
    }

    @Override
    public boolean maybeHas(final Predicate<T> predicate) {
        return this.storage.maybeHas(predicate, this.strategy.globalMap());
    }

    @Override
    public void forEachInPalette(final Consumer<T> consumer) {
        this.storage.forEachInPalette(consumer, this.strategy.globalMap());
    }

    @Override
    public void count(final PalettedContainer.CountConsumer<T> output) {
        this.storage.count(output, this.strategy.globalMap(), this.entryCount);
    }

    @Override
    public PalettedContainer<T> copy() {
        return new OptimizedPalettedContainer<>(this.defaultValue, this.strategy, this.storage.copy(), this.airMask);
    }

    @Override
    public PalettedContainer<T> recreate() {
        return new OptimizedPalettedContainer<>(this.defaultValue, this.strategy);
    }

    private T getAndSet(final int index, final T value) {
        Storage<T> snapshot = this.storage;
        T old = snapshot.valueAt(index, this.strategy.globalMap());
        int id = snapshot.idFor(value, this);
        Storage<T> target = this.storage;
        target.setId(index, id, value, this.strategy.globalMap());
        this.setAirMask(index, value);
        return old;
    }

    private void set(final int index, final T value) {
        Storage<T> snapshot = this.storage;
        int id = snapshot.idFor(value, this);
        Storage<T> target = this.storage;
        target.setId(index, id, value, this.strategy.globalMap());
        this.setAirMask(index, value);
    }

    private BitSet createAirMask(final T value) {
        if (!this.tracksAir) {
            return null;
        }

        BitSet mask = new BitSet(this.entryCount);
        if (isAir(value)) {
            mask.set(0, this.entryCount);
        }

        return mask;
    }

    private void rebuildAirMask() {
        if (!this.tracksAir) {
            return;
        }

        BitSet mask = new BitSet(this.entryCount);
        Storage<T> snapshot = this.storage;
        IdMap<T> globalMap = this.strategy.globalMap();
        for (int i = 0; i < this.entryCount; i++) {
            if (isAir(snapshot.valueAt(i, globalMap))) {
                mask.set(i);
            }
        }

        this.airMask = mask;
    }

    private void setAirMask(final int index, final T value) {
        if (this.airMask != null) {
            this.airMask.set(index, isAir(value));
        }
    }

    private static boolean isAir(final Object value) {
        return value instanceof BlockState blockState && blockState.isAir();
    }

    private Storage<T> growFor(final T lastAddedValue) {
        Storage<T> old = this.storage;
        List<T> values = old.paletteValues(this.strategy.globalMap());
        if (!containsIdentity(values, lastAddedValue)) {
            values.add(lastAddedValue);
        }

        if (values.size() <= this.maxIndirectPaletteSize()) {
            IndirectStorage<T> next = new IndirectStorage<>(this.entryCount, values);
            old.copyInto(next, this.strategy.globalMap());
            return next;
        }

        GlobalStorage<T> next = GlobalStorage.create(this.entryCount, this.strategy.globalMap());
        old.copyInto(next, this.strategy.globalMap());
        return next;
    }

    private Storage<T> storageFromLocalIds(final List<T> palette, final int[] ids) {
        if (palette.size() == 1) {
            return new SingleStorage<>(palette.getFirst());
        }

        if (palette.size() <= this.maxIndirectPaletteSize()) {
            return new IndirectStorage<>(ids, palette);
        }

        int[] globalIds = new int[this.entryCount];
        IdMap<T> globalMap = this.strategy.globalMap();
        for (int i = 0; i < this.entryCount; i++) {
            globalIds[i] = globalMap.getId(palette.get(ids[i]));
        }

        return this.globalStorageFromIds(globalIds);
    }

    private Storage<T> globalStorageFromIds(final int[] globalIds) {
        for (int id : globalIds) {
            if (id > Character.MAX_VALUE) {
                return new IntGlobalStorage<>(globalIds);
            }
        }

        return new CharGlobalStorage<>(globalIds);
    }

    private static boolean containsIdentity(final List<?> values, final Object value) {
        for (Object existing : values) {
            if (existing == value) {
                return true;
            }
        }

        return false;
    }

    private int maxIndirectPaletteSize() {
        return 1 << this.maxIndirectBits;
    }

    private static int storageBitsForWire(final int wireBits, final Strategy<?> strategy) {
        int maxIndirectBits = strategy.entryCount() == 4096 ? 8 : 3;
        if (wireBits <= maxIndirectBits) {
            return wireBits;
        }

        return Math.max(wireBits, ceilLog2(strategy.globalMap().size()));
    }

    private static int packedStorageBits(final int entryCount, final int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }

        int bits = ceilLog2(paletteSize);
        if (entryCount == 4096 && bits <= 4) {
            return 4;
        }

        return bits;
    }

    private static int inferStorageBits(final int entryCount, final int rawLength, final int paletteSize) {
        for (int bits = Math.max(1, packedStorageBits(entryCount, paletteSize)); bits <= 32; bits++) {
            int valuesPerLong = 64 / bits;
            int expectedLength = (entryCount + valuesPerLong - 1) / valuesPerLong;
            if (expectedLength == rawLength) {
                return bits;
            }
        }

        throw new IllegalArgumentException("Cannot infer storage bits from " + rawLength + " longs");
    }

    private static int ceilLog2(final int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private interface Storage<T> {
        T valueAt(int index, IdMap<T> globalMap);

        int idFor(T value, PaletteResize<T> resizeHandler);

        int idForExisting(T value);

        void setId(int index, int id, T value, IdMap<T> globalMap);

        void getAll(Consumer<T> consumer, IdMap<T> globalMap);

        boolean maybeHas(Predicate<T> predicate, IdMap<T> globalMap);

        void forEachInPalette(Consumer<T> consumer, IdMap<T> globalMap);

        void count(PalettedContainer.CountConsumer<T> output, IdMap<T> globalMap, int entryCount);

        void write(FriendlyByteBuf buffer, IdMap<T> globalMap, int entryCount, int maxIndirectBits);

        Packed<T> pack(IdMap<T> globalMap, int entryCount);

        int serializedSize(IdMap<T> globalMap, int entryCount, int maxIndirectBits);

        int bits(int entryCount, int maxIndirectBits, IdMap<T> globalMap);

        Storage<T> copy();

        List<T> paletteValues(IdMap<T> globalMap);

        void copyInto(IndirectStorage<T> target, IdMap<T> globalMap);

        void copyInto(GlobalStorage<T> target, IdMap<T> globalMap);
    }

    private record SingleStorage<T>(T value) implements Storage<T> {
        @Override
        public T valueAt(final int index, final IdMap<T> globalMap) {
            return this.value;
        }

        @Override
        public int idFor(final T value, final PaletteResize<T> resizeHandler) {
            return this.value == value ? 0 : resizeHandler.onResize(1, value);
        }

        @Override
        public int idForExisting(final T value) {
            return 0;
        }

        @Override
        public void setId(final int index, final int id, final T value, final IdMap<T> globalMap) {
        }

        @Override
        public void getAll(final Consumer<T> consumer, final IdMap<T> globalMap) {
            consumer.accept(this.value);
        }

        @Override
        public boolean maybeHas(final Predicate<T> predicate, final IdMap<T> globalMap) {
            return predicate.test(this.value);
        }

        @Override
        public void forEachInPalette(final Consumer<T> consumer, final IdMap<T> globalMap) {
            consumer.accept(this.value);
        }

        @Override
        public void count(final PalettedContainer.CountConsumer<T> output, final IdMap<T> globalMap, final int entryCount) {
            output.accept(this.value, entryCount);
        }

        @Override
        public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            buffer.writeByte(0);
            buffer.writeVarInt(globalMap.getId(this.value));
            buffer.writeFixedSizeLongArray(new long[0]);
        }

        @Override
        public Packed<T> pack(final IdMap<T> globalMap, final int entryCount) {
            return new Packed<>(List.of(this.value), new int[entryCount], 0);
        }

        @Override
        public int serializedSize(final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            return 1 + VarInt.getByteSize(globalMap.getId(this.value));
        }

        @Override
        public int bits(final int entryCount, final int maxIndirectBits, final IdMap<T> globalMap) {
            return 0;
        }

        @Override
        public Storage<T> copy() {
            return this;
        }

        @Override
        public List<T> paletteValues(final IdMap<T> globalMap) {
            return new ArrayList<>(List.of(this.value));
        }

        @Override
        public void copyInto(final IndirectStorage<T> target, final IdMap<T> globalMap) {
            int id = target.idForExisting(this.value);
            Arrays.fill(target.ids, (byte)id);
        }

        @Override
        public void copyInto(final GlobalStorage<T> target, final IdMap<T> globalMap) {
            target.fill(globalMap.getId(this.value));
        }
    }

    private static final class IndirectStorage<T> implements Storage<T> {
        private final byte[] ids;
        private final Object[] palette;
        private final Reference2IntOpenHashMap<T> reverse;
        private int size;

        IndirectStorage(final int entryCount, final List<T> entries) {
            this.ids = new byte[entryCount];
            this.palette = new Object[MAX_BYTE_PALETTE_SIZE];
            this.reverse = new Reference2IntOpenHashMap<>(Math.max(SMALL_PALETTE_SIZE, entries.size() * 2));
            this.reverse.defaultReturnValue(-1);
            for (T entry : entries) {
                this.addPaletteEntry(entry);
            }
        }

        IndirectStorage(final int[] ids, final List<T> entries) {
            this(ids.length, entries);
            for (int i = 0; i < ids.length; i++) {
                this.ids[i] = (byte)ids[i];
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public T valueAt(final int index, final IdMap<T> globalMap) {
            return (T)this.palette[this.ids[index] & 0xFF];
        }

        @Override
        public int idFor(final T value, final PaletteResize<T> resizeHandler) {
            int id = this.find(value);
            if (id >= 0) {
                return id;
            }

            if (this.size >= MAX_BYTE_PALETTE_SIZE) {
                return resizeHandler.onResize(9, value);
            }

            return this.addPaletteEntry(value);
        }

        @Override
        public int idForExisting(final T value) {
            return this.find(value);
        }

        @Override
        public void setId(final int index, final int id, final T value, final IdMap<T> globalMap) {
            this.ids[index] = (byte)id;
        }

        @Override
        public void getAll(final Consumer<T> consumer, final IdMap<T> globalMap) {
            IntOpenHashSet seen = new IntOpenHashSet();
            for (byte id : this.ids) {
                int unsigned = id & 0xFF;
                if (seen.add(unsigned)) {
                    consumer.accept((T)this.palette[unsigned]);
                }
            }
        }

        @Override
        public boolean maybeHas(final Predicate<T> predicate, final IdMap<T> globalMap) {
            for (int i = 0; i < this.size; i++) {
                if (predicate.test((T)this.palette[i])) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public void forEachInPalette(final Consumer<T> consumer, final IdMap<T> globalMap) {
            for (int i = 0; i < this.size; i++) {
                consumer.accept((T)this.palette[i]);
            }
        }

        @Override
        public void count(final PalettedContainer.CountConsumer<T> output, final IdMap<T> globalMap, final int entryCount) {
            // Direct palette-based count: O(entryCount) scanning, O(size) allocation
            // size is bounded to MAX_BYTE_PALETTE_SIZE (256), so allocation is <= 1KB
            int[] counts = new int[this.size];
            final byte[] localIds = this.ids;
            final int localSize = this.size;
            for (int i = 0; i < entryCount; i++) {
                int id = localIds[i] & 0xFF;
                if (id < localSize) {
                    counts[id]++;
                }
            }

            for (int i = 0; i < localSize; i++) {
                if (counts[i] != 0) {
                    output.accept((T)this.palette[i], counts[i]);
                }
            }
        }

        @Override
        public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            int bits = indirectBits(entryCount, this.size);
            if (bits > maxIndirectBits) {
                this.writeAsGlobal(buffer, globalMap, entryCount);
                return;
            }

            buffer.writeByte(bits);
            buffer.writeVarInt(this.size);
            for (int i = 0; i < this.size; i++) {
                buffer.writeVarInt(globalMap.getId((T)this.palette[i]));
            }

            buffer.writeFixedSizeLongArray(new SimpleBitStorage(bits, entryCount, this.toIntIds(entryCount)).getRaw());
        }

        @Override
        public Packed<T> pack(final IdMap<T> globalMap, final int entryCount) {
            int bits = packedStorageBits(entryCount, this.size);
            return new Packed<>(this.paletteValues(globalMap), this.toIntIds(entryCount), bits);
        }

        @Override
        public int serializedSize(final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            int bits = indirectBits(entryCount, this.size);
            if (bits > maxIndirectBits) {
                return 1 + new SimpleBitStorage(ceilLog2(globalMap.size()), entryCount).getRaw().length * Long.BYTES;
            }

            int sizeBytes = 1 + VarInt.getByteSize(this.size);
            for (int i = 0; i < this.size; i++) {
                sizeBytes += VarInt.getByteSize(globalMap.getId((T)this.palette[i]));
            }

            return sizeBytes + new SimpleBitStorage(bits, entryCount).getRaw().length * Long.BYTES;
        }

        @Override
        public int bits(final int entryCount, final int maxIndirectBits, final IdMap<T> globalMap) {
            int bits = indirectBits(entryCount, this.size);
            return bits > maxIndirectBits ? ceilLog2(globalMap.size()) : bits;
        }

        @Override
        public Storage<T> copy() {
            IndirectStorage<T> copy = new IndirectStorage<>(this.ids.length, this.paletteValues(null));
            System.arraycopy(this.ids, 0, copy.ids, 0, this.ids.length);
            return copy;
        }

        @Override
        public List<T> paletteValues(final IdMap<T> globalMap) {
            ArrayList<T> entries = new ArrayList<>(this.size);
            for (int i = 0; i < this.size; i++) {
                entries.add((T)this.palette[i]);
            }

            return entries;
        }

        @Override
        public void copyInto(final IndirectStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.ids[i] = (byte)target.idForExisting((T)this.palette[this.ids[i] & 0xFF]);
            }
        }

        @Override
        public void copyInto(final GlobalStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.setGlobalId(i, globalMap.getId((T)this.palette[this.ids[i] & 0xFF]));
            }
        }

        private int find(final T value) {
            if (this.size <= SMALL_PALETTE_SIZE) {
                for (int i = 0; i < this.size; i++) {
                    if (this.palette[i] == value) {
                        return i;
                    }
                }

                return -1;
            }

            int id = this.reverse.getInt(value);
            if (id >= 0) {
                return id;
            }

            for (int i = 0; i < SMALL_PALETTE_SIZE; i++) {
                if (this.palette[i] == value) {
                    this.reverse.put(value, i);
                    return i;
                }
            }

            return -1;
        }

        private int addPaletteEntry(final T value) {
            int existing = this.find(value);
            if (existing >= 0) {
                return existing;
            }

            int id = this.size++;
            this.palette[id] = value;
            if (id == SMALL_PALETTE_SIZE) {
                for (int i = 0; i <= id; i++) {
                    this.reverse.put((T)this.palette[i], i);
                }
                return id;
            }

            if (this.size > SMALL_PALETTE_SIZE) {
                this.reverse.put(value, id);
            }

            return id;
        }

        private void writeAsGlobal(final FriendlyByteBuf buffer, final IdMap<T> globalMap, final int entryCount) {
            int bits = ceilLog2(globalMap.size());
            int[] globalIds = new int[entryCount];
            for (int i = 0; i < entryCount; i++) {
                globalIds[i] = globalMap.getId((T)this.palette[this.ids[i] & 0xFF]);
            }

            buffer.writeByte(bits);
            buffer.writeFixedSizeLongArray(new SimpleBitStorage(bits, entryCount, globalIds).getRaw());
        }

        private int[] toIntIds(final int entryCount) {
            int[] out = new int[entryCount];
            for (int i = 0; i < entryCount; i++) {
                out[i] = this.ids[i] & 0xFF;
            }

            return out;
        }

        @SuppressWarnings("unchecked")
        private T entry(final int id) {
            return (T)this.palette[id];
        }
    }

    private interface GlobalStorage<T> extends Storage<T> {
        void setGlobalId(int index, int globalId);

        void fill(int globalId);

        static <T> GlobalStorage<T> create(final int entryCount, final IdMap<T> globalMap) {
            return globalMap.size() <= Character.MAX_VALUE ? new CharGlobalStorage<>(entryCount) : new IntGlobalStorage<>(entryCount);
        }
    }

    private static final class CharGlobalStorage<T> implements GlobalStorage<T> {
        private final char[] ids;
        private Int2IntOpenHashMap counts;

        CharGlobalStorage(final int entryCount) {
            this.ids = new char[entryCount];
            this.counts = new Int2IntOpenHashMap();
            this.counts.defaultReturnValue(0);
            this.counts.put(0, entryCount);
        }

        CharGlobalStorage(final int[] ids) {
            this.ids = new char[ids.length];
            for (int i = 0; i < ids.length; i++) {
                this.ids[i] = (char)ids[i];
            }
            this.counts = buildCounts(ids);
        }

        @Override
        public T valueAt(final int index, final IdMap<T> globalMap) {
            return globalMap.byIdOrThrow(this.ids[index]);
        }

        @Override
        public int idFor(final T value, final PaletteResize<T> resizeHandler) {
            return 0;
        }

        @Override
        public int idForExisting(final T value) {
            return 0;
        }

        @Override
        public void setId(final int index, final int id, final T value, final IdMap<T> globalMap) {
            this.setGlobalId(index, globalMap.getId(value));
        }

        @Override
        public void getAll(final Consumer<T> consumer, final IdMap<T> globalMap) {
            if (this.counts != null) {
                this.counts.keySet().forEach(id -> consumer.accept(globalMap.byIdOrThrow(id)));
                return;
            }

            IntOpenHashSet seen = new IntOpenHashSet();
            for (char id : this.ids) {
                if (seen.add(id)) {
                    consumer.accept(globalMap.byIdOrThrow(id));
                }
            }
        }

        @Override
        public boolean maybeHas(final Predicate<T> predicate, final IdMap<T> globalMap) {
            if (this.counts != null) {
                for (int id : this.counts.keySet()) {
                    if (predicate.test(globalMap.byIdOrThrow(id))) {
                        return true;
                    }
                }

                return false;
            }

            return true;
        }

        @Override
        public void forEachInPalette(final Consumer<T> consumer, final IdMap<T> globalMap) {
            if (this.counts != null) {
                this.counts.keySet().forEach(id -> consumer.accept(globalMap.byIdOrThrow(id)));
            } else {
                globalMap.forEach(consumer);
            }
        }

        @Override
        public void count(final PalettedContainer.CountConsumer<T> output, final IdMap<T> globalMap, final int entryCount) {
            if (this.counts != null) {
                this.counts.int2IntEntrySet().forEach(entry -> output.accept(globalMap.byIdOrThrow(entry.getIntKey()), entry.getIntValue()));
                return;
            }

            Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
            for (char id : this.ids) {
                counts.addTo(id, 1);
            }
            counts.int2IntEntrySet().forEach(entry -> output.accept(globalMap.byIdOrThrow(entry.getIntKey()), entry.getIntValue()));
        }

        @Override
        public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            int bits = ceilLog2(globalMap.size());
            buffer.writeByte(bits);
            buffer.writeFixedSizeLongArray(new SimpleBitStorage(bits, entryCount, this.toIntIds(entryCount)).getRaw());
        }

        @Override
        public Packed<T> pack(final IdMap<T> globalMap, final int entryCount) {
            return compactGlobal(this.toIntIds(entryCount), globalMap, entryCount);
        }

        @Override
        public int serializedSize(final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            return 1 + new SimpleBitStorage(this.bits(entryCount, maxIndirectBits, globalMap), entryCount).getRaw().length * Long.BYTES;
        }

        @Override
        public int bits(final int entryCount, final int maxIndirectBits, final IdMap<T> globalMap) {
            return ceilLog2(globalMap.size());
        }

        @Override
        public Storage<T> copy() {
            CharGlobalStorage<T> copy = new CharGlobalStorage<>(this.ids.length);
            System.arraycopy(this.ids, 0, copy.ids, 0, this.ids.length);
            copy.counts = copyCounts(this.counts);
            return copy;
        }

        @Override
        public List<T> paletteValues(final IdMap<T> globalMap) {
            if (this.counts != null) {
                ArrayList<T> values = new ArrayList<>(this.counts.size());
                this.counts.keySet().forEach(id -> values.add(globalMap.byIdOrThrow(id)));
                return values;
            }

            ArrayList<T> values = new ArrayList<>();
            IntOpenHashSet seen = new IntOpenHashSet();
            for (char id : this.ids) {
                if (seen.add(id)) {
                    values.add(globalMap.byIdOrThrow(id));
                }
            }
            return values;
        }

        @Override
        public void copyInto(final IndirectStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.ids[i] = (byte)target.idForExisting(globalMap.byIdOrThrow(this.ids[i]));
            }
        }

        @Override
        public void copyInto(final GlobalStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.setGlobalId(i, this.ids[i]);
            }
        }

        @Override
        public void setGlobalId(final int index, final int globalId) {
            int oldId = this.ids[index];
            this.ids[index] = (char)globalId;
            this.updateCount(oldId, globalId);
        }

        @Override
        public void fill(final int globalId) {
            Arrays.fill(this.ids, (char)globalId);
            this.counts = new Int2IntOpenHashMap();
            this.counts.defaultReturnValue(0);
            this.counts.put(globalId, this.ids.length);
        }

        private void updateCount(final int oldId, final int newId) {
            if (this.counts == null || oldId == newId) {
                return;
            }

            decrement(this.counts, oldId);
            incrementOrDrop(this, newId);
        }

        private int[] toIntIds(final int entryCount) {
            int[] out = new int[entryCount];
            for (int i = 0; i < entryCount; i++) {
                out[i] = this.ids[i];
            }
            return out;
        }
    }

    private static final class IntGlobalStorage<T> implements GlobalStorage<T> {
        private final int[] ids;
        private Int2IntOpenHashMap counts;

        IntGlobalStorage(final int entryCount) {
            this.ids = new int[entryCount];
            this.counts = new Int2IntOpenHashMap();
            this.counts.defaultReturnValue(0);
            this.counts.put(0, entryCount);
        }

        IntGlobalStorage(final int[] ids) {
            this.ids = ids.clone();
            this.counts = buildCounts(ids);
        }

        @Override
        public T valueAt(final int index, final IdMap<T> globalMap) {
            return globalMap.byIdOrThrow(this.ids[index]);
        }

        @Override
        public int idFor(final T value, final PaletteResize<T> resizeHandler) {
            return 0;
        }

        @Override
        public int idForExisting(final T value) {
            return 0;
        }

        @Override
        public void setId(final int index, final int id, final T value, final IdMap<T> globalMap) {
            this.setGlobalId(index, globalMap.getId(value));
        }

        @Override
        public void getAll(final Consumer<T> consumer, final IdMap<T> globalMap) {
            if (this.counts != null) {
                this.counts.keySet().forEach(id -> consumer.accept(globalMap.byIdOrThrow(id)));
                return;
            }

            IntOpenHashSet seen = new IntOpenHashSet();
            for (int id : this.ids) {
                if (seen.add(id)) {
                    consumer.accept(globalMap.byIdOrThrow(id));
                }
            }
        }

        @Override
        public boolean maybeHas(final Predicate<T> predicate, final IdMap<T> globalMap) {
            if (this.counts != null) {
                for (int id : this.counts.keySet()) {
                    if (predicate.test(globalMap.byIdOrThrow(id))) {
                        return true;
                    }
                }

                return false;
            }

            return true;
        }

        @Override
        public void forEachInPalette(final Consumer<T> consumer, final IdMap<T> globalMap) {
            if (this.counts != null) {
                this.counts.keySet().forEach(id -> consumer.accept(globalMap.byIdOrThrow(id)));
            } else {
                globalMap.forEach(consumer);
            }
        }

        @Override
        public void count(final PalettedContainer.CountConsumer<T> output, final IdMap<T> globalMap, final int entryCount) {
            if (this.counts != null) {
                this.counts.int2IntEntrySet().forEach(entry -> output.accept(globalMap.byIdOrThrow(entry.getIntKey()), entry.getIntValue()));
                return;
            }

            Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
            for (int id : this.ids) {
                counts.addTo(id, 1);
            }
            counts.int2IntEntrySet().forEach(entry -> output.accept(globalMap.byIdOrThrow(entry.getIntKey()), entry.getIntValue()));
        }

        @Override
        public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            int bits = ceilLog2(globalMap.size());
            buffer.writeByte(bits);
            buffer.writeFixedSizeLongArray(new SimpleBitStorage(bits, entryCount, this.ids).getRaw());
        }

        @Override
        public Packed<T> pack(final IdMap<T> globalMap, final int entryCount) {
            return compactGlobal(this.ids, globalMap, entryCount);
        }

        @Override
        public int serializedSize(final IdMap<T> globalMap, final int entryCount, final int maxIndirectBits) {
            return 1 + new SimpleBitStorage(this.bits(entryCount, maxIndirectBits, globalMap), entryCount).getRaw().length * Long.BYTES;
        }

        @Override
        public int bits(final int entryCount, final int maxIndirectBits, final IdMap<T> globalMap) {
            return ceilLog2(globalMap.size());
        }

        @Override
        public Storage<T> copy() {
            IntGlobalStorage<T> copy = new IntGlobalStorage<>(this.ids);
            copy.counts = copyCounts(this.counts);
            return copy;
        }

        @Override
        public List<T> paletteValues(final IdMap<T> globalMap) {
            if (this.counts != null) {
                ArrayList<T> values = new ArrayList<>(this.counts.size());
                this.counts.keySet().forEach(id -> values.add(globalMap.byIdOrThrow(id)));
                return values;
            }

            ArrayList<T> values = new ArrayList<>();
            IntOpenHashSet seen = new IntOpenHashSet();
            for (int id : this.ids) {
                if (seen.add(id)) {
                    values.add(globalMap.byIdOrThrow(id));
                }
            }
            return values;
        }

        @Override
        public void copyInto(final IndirectStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.ids[i] = (byte)target.idForExisting(globalMap.byIdOrThrow(this.ids[i]));
            }
        }

        @Override
        public void copyInto(final GlobalStorage<T> target, final IdMap<T> globalMap) {
            for (int i = 0; i < this.ids.length; i++) {
                target.setGlobalId(i, this.ids[i]);
            }
        }

        @Override
        public void setGlobalId(final int index, final int globalId) {
            int oldId = this.ids[index];
            this.ids[index] = globalId;
            this.updateCount(oldId, globalId);
        }

        @Override
        public void fill(final int globalId) {
            Arrays.fill(this.ids, globalId);
            this.counts = new Int2IntOpenHashMap();
            this.counts.defaultReturnValue(0);
            this.counts.put(globalId, this.ids.length);
        }

        private void updateCount(final int oldId, final int newId) {
            if (this.counts == null || oldId == newId) {
                return;
            }

            decrement(this.counts, oldId);
            incrementOrDrop(this, newId);
        }
    }

    private record Packed<T>(List<T> palette, int[] ids, int bits) {
    }

    private static <T> Packed<T> compactGlobal(final int[] globalIds, final IdMap<T> globalMap, final int entryCount) {
        Reference2IntOpenHashMap<T> reverse = new Reference2IntOpenHashMap<>();
        reverse.defaultReturnValue(-1);
        ArrayList<T> palette = new ArrayList<>();
        int[] localIds = new int[entryCount];

        for (int i = 0; i < entryCount; i++) {
            T value = globalMap.byIdOrThrow(globalIds[i]);
            int local = reverse.getInt(value);
            if (local < 0) {
                local = palette.size();
                palette.add(value);
                reverse.put(value, local);
            }

            localIds[i] = local;
        }

        return new Packed<>(palette, localIds, packedStorageBits(entryCount, palette.size()));
    }

    private static Int2IntOpenHashMap buildCounts(final int[] ids) {
        Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
        counts.defaultReturnValue(0);
        for (int id : ids) {
            counts.addTo(id, 1);
            if (counts.size() > GLOBAL_COUNT_INDEX_LIMIT) {
                return null;
            }
        }

        return counts;
    }

    private static Int2IntOpenHashMap copyCounts(final Int2IntOpenHashMap counts) {
        return counts == null ? null : new Int2IntOpenHashMap(counts);
    }

    private static void decrement(final Int2IntOpenHashMap counts, final int id) {
        int count = counts.get(id);
        if (count <= 1) {
            counts.remove(id);
        } else {
            counts.put(id, count - 1);
        }
    }

    private static void incrementOrDrop(final CharGlobalStorage<?> storage, final int id) {
        storage.counts.addTo(id, 1);
        if (storage.counts.size() > GLOBAL_COUNT_INDEX_LIMIT) {
            storage.counts = null;
        }
    }

    private static void incrementOrDrop(final IntGlobalStorage<?> storage, final int id) {
        storage.counts.addTo(id, 1);
        if (storage.counts.size() > GLOBAL_COUNT_INDEX_LIMIT) {
            storage.counts = null;
        }
    }

    private static int indirectBits(final int entryCount, final int paletteSize) {
        int bits = ceilLog2(paletteSize);
        if (entryCount == 4096 && bits <= 4) {
            return 4;
        }

        return Math.max(1, bits);
    }
}
