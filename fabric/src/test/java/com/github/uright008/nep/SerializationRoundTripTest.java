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

/**
 * W2 dim-3 serialization round-trip tests (String level, no Minecraft bootstrap).
 *
 * <p>Each test writes an {@link OptimizedPalettedContainer} and reads it back
 * through the same wire format, asserting cell equality, bit-count preservation
 * and full buffer consumption.</p>
 *
 * <p>Sizes cover every wire regime: single, indirect bits 4/8, the previously
 * broken indirect bit 7 (100 distinct), and biome-global bits 4.</p>
 */
class SerializationRoundTripTest {

    /** Block indirect, bits 4 (palette size 2..16). */
    private static final int INDIRECT_BITS4 = 16;
    /** Block indirect, bits 7 (palette size 65..128 — the regime packBits used to mis-size). */
    private static final int INDIRECT_BITS7 = 100;
    /** Block indirect, bits 8 (palette size 129..256). */
    private static final int INDIRECT_BITS8 = 200;
    /** Biome global, bits 4 (palette size 9..16 with a 16-value id map). */
    private static final int BIOME_GLOBAL = 10;

    /** Expected wire bits for a block section holding {@code distinct} values (min 4, as in Strategy). */
    private static int expectedBits(int distinct) {
        int bits = distinct <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(distinct - 1);
        return Math.max(4, bits);
    }

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
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(container, idMap, size);
            assertThat(container.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(expectedBits(size));

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
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
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
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
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
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
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

        // indirect bits 4, 7 and 8
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
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
    void roundTrip_bitsNotDividing64_roundTrips() {
        // Regression for the packBits under-allocation bug: bits 7 (100 distinct)
        // does not divide 64, and write() used to throw ArrayIndexOutOfBoundsException.
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(container, idMap, INDIRECT_BITS7);
        assertThat(container.bitsPerEntry()).isEqualTo(7);

        FriendlyByteBuf buffer = write(container);
        OptimizedPalettedContainer<String> restored =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        restored.read(buffer);

        assertThat(restored.bitsPerEntry()).isEqualTo(7);
        TestContainers.assertCellsEqual(container, restored);
        assertThat(buffer.readerIndex()).isEqualTo(buffer.writerIndex());
    }
}
