package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 dim-3 cross-implementation compatibility tests (String level).
 *
 * <p>Both writers are byte-compared, and each implementation is asked to read
 * what the other wrote. This is the strongest possible serialization contract:
 * NEP must be a drop-in replacement for the vanilla {@link PalettedContainer}
 * on the wire.</p>
 *
 * <p>Sizes cover every wire regime including indirect bit 7 (100 distinct) and
 * global bit 9 (300 distinct) — the bit counts that previously crashed NEP's
 * {@code write()} due to a {@code packBits} under-allocation bug.</p>
 */
class CrossImplementationCompatibilityTest {

    /** Block indirect, bits 4 (palette size 2..16). */
    private static final int INDIRECT_BITS4 = 16;
    /** Block indirect, bits 7 (palette size 65..128 — the regime packBits used to mis-size). */
    private static final int INDIRECT_BITS7 = 100;
    /** Block indirect, bits 8 (palette size 129..256). */
    private static final int INDIRECT_BITS8 = 200;
    /** Block global, bits 9 (palette size 257..300 against a 300-value id map). */
    private static final int GLOBAL_BITS9 = 300;
    /** Biome global, bits 4 (palette size 9..16 with a 16-value id map). */
    private static final int BIOME_GLOBAL = 10;

    private static FriendlyByteBuf writeTo(PalettedContainer<String> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer);
        return buffer;
    }

    private static byte[] writtenBytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    @Test
    void wireFormat_byteIdentical_single() {
        TestIdMap idMap = TestContainers.blockIdMap(4);
        PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);

        byte[] vanillaBytes = writtenBytes(writeTo(vanilla));
        byte[] nepBytes = writtenBytes(writeTo(nep));
        assertThat(nepBytes).isEqualTo(vanillaBytes);
    }

    @Test
    void wireFormat_byteIdentical_indirect() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
            OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(vanilla, idMap, size);
            TestContainers.fillDistinct(nep, idMap, size);

            assertThat(nep.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            byte[] vanillaBytes = writtenBytes(writeTo(vanilla));
            byte[] nepBytes = writtenBytes(writeTo(nep));
            assertThat(nepBytes).as("wire bytes at %d distinct", size).isEqualTo(vanillaBytes);
        }
    }

    @Test
    void wireFormat_byteIdentical_global() {
        // A biome section with 10 distinct values (> 2^3) and a 16-value id map
        // forces global storage at 4 wire bits — byte-identical in both writers.
        TestIdMap idMap = TestContainers.biomeIdMap(16);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(idMap), idMap);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinctBiomes(vanilla, idMap, BIOME_GLOBAL);
        TestContainers.fillDistinctBiomes(nep, idMap, BIOME_GLOBAL);

        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry()).isEqualTo(4);
        byte[] vanillaBytes = writtenBytes(writeTo(vanilla));
        byte[] nepBytes = writtenBytes(writeTo(nep));
        assertThat(nepBytes).isEqualTo(vanillaBytes);
    }

    @Test
    void nepWrite_vanillaRead_valueEquivalent() {
        // single
        assertNepWriteVanillaReadBlock(1);
        // indirect bits 4, 7 and 8; global bits 9
        assertNepWriteVanillaReadBlock(INDIRECT_BITS4);
        assertNepWriteVanillaReadBlock(INDIRECT_BITS7);
        assertNepWriteVanillaReadBlock(INDIRECT_BITS8);
        assertNepWriteVanillaReadBlock(GLOBAL_BITS9);
        // global (biome, bits 4)
        TestIdMap idMap = TestContainers.biomeIdMap(16);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinctBiomes(nep, idMap, BIOME_GLOBAL);
        FriendlyByteBuf buffer = writeTo(nep);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        vanilla.read(buffer);
        TestContainers.assertBiomeCellsEqual(nep, vanilla);
    }

    private static void assertNepWriteVanillaReadBlock(int distinct) {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(nep, idMap, distinct);
        FriendlyByteBuf buffer = writeTo(nep);
        PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        vanilla.read(buffer);
        TestContainers.assertCellsEqual(nep, vanilla);
    }

    @Test
    void vanillaWrite_nepRead_valueEquivalent() {
        // Covers single, indirect bits 4/7/8, and global bits 9.
        int[] sizes = {1, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8, GLOBAL_BITS9};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(vanilla, idMap, size);
            FriendlyByteBuf buffer = writeTo(vanilla);
            OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            buffer.readerIndex(0);
            nep.read(buffer);
            assertThat(nep.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            TestContainers.assertCellsEqual(vanilla, nep);
        }

        TestIdMap biomeMap = TestContainers.biomeIdMap(16);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        TestContainers.fillDistinctBiomes(vanilla, biomeMap, BIOME_GLOBAL);
        FriendlyByteBuf buffer = writeTo(vanilla);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        buffer.readerIndex(0);
        nep.read(buffer);
        TestContainers.assertBiomeCellsEqual(vanilla, nep);
    }

    @Test
    void nepPack_vanillaUnpack_valueEquivalent() {
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8, GLOBAL_BITS9};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            Strategy<String> strategy = Strategy.createForBlockStates(idMap);
            OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(nep, idMap, size);

            PalettedContainerRO.PackedData<String> packed = nep.pack(strategy);
            DataResult<PalettedContainer<String>> result = PalettedContainer.unpack(strategy, packed);
            assertThat(result.error()).as("vanilla unpack of NEP pack at %d distinct", size).isEmpty();
            PalettedContainer<String> vanilla = result.result().orElseThrow();
            TestContainers.assertCellsEqual(nep, vanilla);
        }

        TestIdMap biomeMap = TestContainers.biomeIdMap(16);
        Strategy<String> biomeStrategy = Strategy.createForBiomes(biomeMap);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        TestContainers.fillDistinctBiomes(nep, biomeMap, BIOME_GLOBAL);
        PalettedContainerRO.PackedData<String> packed = nep.pack(biomeStrategy);
        DataResult<PalettedContainer<String>> result = PalettedContainer.unpack(biomeStrategy, packed);
        assertThat(result.error()).isEmpty();
        PalettedContainer<String> vanilla = result.result().orElseThrow();
        TestContainers.assertBiomeCellsEqual(nep, vanilla);
    }

    @Test
    void vanillaPack_nepUnpack_valueEquivalent() {
        int[] sizes = {1, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8, GLOBAL_BITS9};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            Strategy<String> strategy = Strategy.createForBlockStates(idMap);
            PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(vanilla, idMap, size);

            PalettedContainerRO.PackedData<String> packed = vanilla.pack(strategy);
            DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(strategy, packed);
            assertThat(result.error()).as("NEP unpack of vanilla pack at %d distinct", size).isEmpty();
            PalettedContainer<String> nep = result.result().orElseThrow();
            assertThat(nep.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            TestContainers.assertCellsEqual(vanilla, nep);
        }

        TestIdMap biomeMap = TestContainers.biomeIdMap(16);
        Strategy<String> biomeStrategy = Strategy.createForBiomes(biomeMap);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        TestContainers.fillDistinctBiomes(vanilla, biomeMap, BIOME_GLOBAL);
        PalettedContainerRO.PackedData<String> packed = vanilla.pack(biomeStrategy);
        DataResult<PalettedContainer<String>> result = OptimizedPalettedContainer.unpack(biomeStrategy, packed);
        assertThat(result.error()).isEmpty();
        PalettedContainer<String> nep = result.result().orElseThrow();
        TestContainers.assertBiomeCellsEqual(vanilla, nep);
    }

    @Test
    void serializedSize_matchesBothWriters() {
        // single
        TestIdMap singleMap = TestContainers.blockIdMap(4);
        assertSizesMatch(
                TestContainers.vanillaBlocks(TestContainers.defaultValue(singleMap), singleMap),
                TestContainers.nepBlocks(TestContainers.defaultValue(singleMap), singleMap),
                "single");

        // indirect bits 4, 7 and 8
        int[] sizes = {5, INDIRECT_BITS4, INDIRECT_BITS7, INDIRECT_BITS8};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
            OptimizedPalettedContainer<String> nep = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(vanilla, idMap, size);
            TestContainers.fillDistinct(nep, idMap, size);
            assertSizesMatch(vanilla, nep, "indirect " + size);
        }

        // biome global
        TestIdMap biomeMap = TestContainers.biomeIdMap(16);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        OptimizedPalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(biomeMap), biomeMap);
        TestContainers.fillDistinctBiomes(vanilla, biomeMap, BIOME_GLOBAL);
        TestContainers.fillDistinctBiomes(nep, biomeMap, BIOME_GLOBAL);
        assertSizesMatch(vanilla, nep, "biome-global");
    }

    private static void assertSizesMatch(PalettedContainer<String> vanilla, OptimizedPalettedContainer<String> nep, String label) {
        assertThat(nep.getSerializedSize()).as("getSerializedSize (%s)", label).isEqualTo(vanilla.getSerializedSize());
        assertThat(writtenBytes(writeTo(nep))).as("written bytes == getSerializedSize (%s)", label).hasSize(nep.getSerializedSize());
    }
}
