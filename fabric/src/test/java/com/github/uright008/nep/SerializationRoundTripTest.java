package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2 dim-3 serialization round-trip tests (String level, no Minecraft bootstrap).
 *
 * <p>Each test writes an {@link OptimizedPalettedContainer} and reads it back
 * through the same wire format, asserting cell equality, bit-count preservation
 * and full buffer consumption.</p>
 *
 * <p><b>Known NEP bug (recorded, not fixed in this wave):</b>
 * {@code OptimizedPalettedContainer.packBits} allocates its long[] via
 * {@code storageLongs(entryCount, bits) = (entryCount*bits+63)/64}, but packs
 * {@code 64/bits} values per long. These formulas agree only when {@code 64/bits}
 * is exact, i.e. {@code bits in {1,2,4,8,16,32}}. For every other bit count
 * (indirect 5,6,7 and global 9+) the allocation is too small and {@code write()}
 * / {@code pack()} throw {@link ArrayIndexOutOfBoundsException}. The vanilla
 * {@code SimpleBitStorage} uses {@code ceil(entryCount / (64/bits))}, so this is
 * a genuine NEP defect. {@link #nepWrite_bitsNotDividing64_knownBug_throwsAIOOBE}
 * pins the crash; the round-trip tests here therefore cover the working regimes
 * (single, indirect bits 4/8, biome-global bits 4).</p>
 */
class SerializationRoundTripTest {

    /** Block indirect, bits 4 (palette size 2..16). */
    private static final int INDIRECT_BITS4 = 16;
    /** Block indirect, bits 8 (palette size 129..256). */
    private static final int INDIRECT_BITS8 = 200;
    /** Biome global, bits 4 (palette size 9..16 with a 16-value id map). */
    private static final int BIOME_GLOBAL = 10;

    private static FriendlyByteBuf write(PalettedContainer<String> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer);
        return buffer;
    }

    private static Strategy<String> blockStrategy(TestIdMap idMap) {
        return Strategy.createForBlockStates(idMap);
    }

    private static Strategy<String> biomeStrategy(TestIdMap idMap) {
        return Strategy.createForBiomes(idMap);
    }

    @Test
    void roundTrip_nepWrite_nepRead_single() {
        TestIdMap idMap = TestContainers.blockIdMap(4);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        assertThat(container.bitsPerEntry()).isZero();

        FriendlyByteBuf buffer = write(container);
        OptimizedPalettedContainer<String> restored =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        restored.read(buffer);

        assertThat(restored.bitsPerEntry()).isZero();
        assertThat(restored.get(0, 0, 0)).isEqualTo(TestContainers.defaultValue(idMap));
        assertThat(restored.get(15, 15, 15)).isEqualTo(TestContainers.defaultValue(idMap));
        assertThat(buffer.readerIndex()).isEqualTo(buffer.writerIndex());
    }

    @Test
    void roundTrip_nepWrite_nepRead_indirect() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);
            assertThat(container.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(size > 128 ? 8 : 4);

            FriendlyByteBuf buffer = write(container);
            OptimizedPalettedContainer<String> restored =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            buffer.readerIndex(0);
            restored.read(buffer);

            assertThat(restored.bitsPerEntry()).as("bits preserved at %d distinct", size).isEqualTo(container.bitsPerEntry());
            TestContainers.assertCellsEqual(container, restored);
            assertThat(buffer.readerIndex()).as("fully consumed at %d distinct", size).isEqualTo(buffer.writerIndex());
        }
    }

    @Test
    void roundTrip_nepWrite_nepRead_global() {
        // A biome section with > 2^3 distinct values and a 16-value id map forces
        // global storage at 4 wire bits — the only global regime NEP can currently write.
        TestIdMap idMap = TestContainers.biomeIdMap(16);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinctBiomes(container, idMap, BIOME_GLOBAL);
        assertThat(container.bitsPerEntry()).isEqualTo(4);

        FriendlyByteBuf buffer = write(container);
        OptimizedPalettedContainer<String> restored =
                TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        restored.read(buffer);

        assertThat(restored.bitsPerEntry()).isEqualTo(container.bitsPerEntry());
        TestContainers.assertBiomeCellsEqual(container, restored);
        assertThat(buffer.readerIndex()).isEqualTo(buffer.writerIndex());
    }

    @Test
    void roundTrip_pack_unpack_roundTrip() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            Strategy<String> strategy = blockStrategy(idMap);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);

            PalettedContainerRO.PackedData<String> packed = container.pack(strategy);
            DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, packed);
            assertThat(result.error()).as("unpack error at %d distinct", size).isEmpty();
            PalettedContainer<String> restored = result.result().orElseThrow();
            TestContainers.assertCellsEqual(container, restored);
        }

        TestIdMap idMap = TestContainers.biomeIdMap(16);
        Strategy<String> strategy = biomeStrategy(idMap);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinctBiomes(container, idMap, BIOME_GLOBAL);
        PalettedContainerRO.PackedData<String> packed = container.pack(strategy);
        DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, packed);
        assertThat(result.error()).isEmpty();
        PalettedContainer<String> restored = result.result().orElseThrow();
        TestContainers.assertBiomeCellsEqual(container, restored);
    }

    @Test
    void roundTrip_pack_unpack_preservesBits() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            Strategy<String> strategy = blockStrategy(idMap);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);

            PalettedContainerRO.PackedData<String> packed = container.pack(strategy);
            DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, packed);
            PalettedContainer<String> restored = result.result().orElseThrow();
            assertThat(restored.bitsPerEntry()).as("bits preserved at %d distinct", size).isEqualTo(container.bitsPerEntry());
        }

        TestIdMap idMap = TestContainers.biomeIdMap(16);
        Strategy<String> strategy = biomeStrategy(idMap);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinctBiomes(container, idMap, BIOME_GLOBAL);
        PalettedContainerRO.PackedData<String> packed = container.pack(strategy);
        PalettedContainer<String> restored = OptimizedPalettedContainer.unpack(strategy, packed).result().orElseThrow();
        assertThat(restored.bitsPerEntry()).isEqualTo(container.bitsPerEntry());
    }

    @Test
    void read_rejectsDeclaredBitsMismatch() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        Strategy<String> strategy = blockStrategy(idMap);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(container, idMap, INDIRECT_BITS4);
        PalettedContainerRO.PackedData<String> packed = container.pack(strategy);
        assertThat(packed.bitsPerEntry()).isEqualTo(4);

        // Declared bits must equal the bits implied by the palette size.
        PalettedContainerRO.PackedData<String> wrongBits =
                new PalettedContainerRO.PackedData<>(packed.paletteEntries(), packed.storage(), packed.bitsPerEntry() + 1);
        DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, wrongBits);
        assertThat(result.error()).isPresent();
        assertThat(result.result()).isEmpty();
    }

    @Test
    void read_unknownBits_infersFromRawLength() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            Strategy<String> strategy = blockStrategy(idMap);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);
            PalettedContainerRO.PackedData<String> packed = container.pack(strategy);

            // The two-arg constructor sets bitsPerEntry to UNKNOWN_BITS_PER_ENTRY (-1),
            // forcing inference from the raw storage length.
            PalettedContainerRO.PackedData<String> unknownBits =
                    new PalettedContainerRO.PackedData<>(packed.paletteEntries(), packed.storage());
            assertThat(unknownBits.bitsPerEntry()).isEqualTo(PalettedContainerRO.PackedData.UNKNOWN_BITS_PER_ENTRY);

            DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, unknownBits);
            assertThat(result.error()).as("inference error at %d distinct", size).isEmpty();
            PalettedContainer<String> restored = result.result().orElseThrow();
            assertThat(restored.bitsPerEntry()).as("inferred bits at %d distinct", size).isEqualTo(container.bitsPerEntry());
            TestContainers.assertCellsEqual(container, restored);
        }
    }

    @Test
    void unpack_emptyPalette_returnsError() {
        TestIdMap idMap = TestContainers.blockIdMap(4);
        Strategy<String> strategy = blockStrategy(idMap);
        PalettedContainerRO.PackedData<String> empty =
                new PalettedContainerRO.PackedData<>(List.of(), Optional.empty(), 0);

        DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, empty);
        assertThat(result.error()).isPresent();
        assertThat(result.result()).isEmpty();
    }

    @Test
    void getSerializedSize_matchesBytesActuallyWritten() {
        // single
        TestIdMap singleMap = TestContainers.blockIdMap(4);
        OptimizedPalettedContainer<String> single =
                TestContainers.nepBlocks(TestContainers.defaultValue(singleMap), singleMap);
        assertSerializedSizeMatchesWrite(single, "single");

        // indirect bits 4 and 8
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);
            assertSerializedSizeMatchesWrite(container, "indirect " + size);
        }

        // biome global
        TestIdMap biomeMap = TestContainers.biomeIdMap(16);
        OptimizedPalettedContainer<String> global =
                TestContainers.nepBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        TestContainers.fillDistinctBiomes(global, biomeMap, BIOME_GLOBAL);
        assertSerializedSizeMatchesWrite(global, "biome-global");
    }

    private static void assertSerializedSizeMatchesWrite(OptimizedPalettedContainer<String> container, String label) {
        FriendlyByteBuf buffer = write(container);
        assertThat(buffer.readableBytes()).as("written bytes == getSerializedSize (%s)", label).isEqualTo(container.getSerializedSize());
    }

    @Test
    void nepWrite_bitsNotDividing64_knownBug_throwsAIOOBE() {
        // RECORDED NEP BUG (not fixed in this wave): packBits allocates
        // (entryCount*bits+63)/64 longs but packs 64/bits values per long, which
        // under-allocates for every bit count where 64/bits is not exact (bits 5,6,7
        // and global 9+). The vanilla SimpleBitStorage requires ceil(entryCount/(64/bits)).
        // A 100-distinct block section (bits 7) must therefore throw on write().
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(container, idMap, 100);
        assertThat(container.bitsPerEntry()).isEqualTo(7);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        assertThatThrownBy(() -> container.write(buffer))
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }
}
