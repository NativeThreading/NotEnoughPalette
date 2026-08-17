package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the 4-bit nibble storage mode in
 * {@link OptimizedPalettedContainer}.IndirectStorage.
 *
 * <p>Palettes of 16 or fewer distinct values are packed two per byte
 * (halving the backing array to match vanilla's 4-bit packed size); adding a
 * 17th distinct value must transparently expand the storage back to one byte
 * per cell without losing any cell.</p>
 */
class NibbleStorageTest {

    private static FriendlyByteBuf write(OptimizedPalettedContainer<String> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer);
        return buffer;
    }

    private static void assertRoundTrip(OptimizedPalettedContainer<String> container, TestIdMap idMap) {
        FriendlyByteBuf buffer = write(container);
        OptimizedPalettedContainer<String> restored =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        buffer.readerIndex(0);
        restored.read(buffer);
        assertThat(restored.bitsPerEntry()).isEqualTo(container.bitsPerEntry());
        TestContainers.assertCellsEqual(container, restored);
        assertThat(buffer.readerIndex()).isEqualTo(buffer.writerIndex());
    }

    @Test
    void nibbleMode_16Distinct_bits4_roundTrip_copy_getAll() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(container, idMap, 16);

        // 16 distinct values stay in the 4-bit nibble regime.
        assertThat(container.bitsPerEntry()).isEqualTo(4);

        // Serialization round-trip preserves every cell.
        assertRoundTrip(container, idMap);

        // Copy is independent and identical.
        net.minecraft.world.level.chunk.PalettedContainer<String> copy = container.copy();
        TestContainers.assertCellsEqual(container, copy);
        copy.set(0, 0, 0, idMap.byId(200));
        assertThat(copy.get(0, 0, 0)).isEqualTo(idMap.byId(200));
        assertThat(container.get(0, 0, 0)).isEqualTo(idMap.byId(0));

        // getAll sees exactly the 16 distinct values present.
        assertThat(TestContainers.distinctPresent(container)).hasSize(16);
    }

    @Test
    void nibbleGrow_17thDistinct_expandsToByteMode_keepsCells() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(container, idMap, 16);
        TestContainers.fillDistinct(container, idMap, 17);

        // A 17th distinct value pushes out of the 4-bit regime into byte mode.
        assertThat(container.bitsPerEntry()).isEqualTo(5);

        // Every cell survived the nibble -> byte expansion.
        assertRoundTrip(container, idMap);

        net.minecraft.world.level.chunk.PalettedContainer<String> copy = container.copy();
        TestContainers.assertCellsEqual(container, copy);
        assertThat(TestContainers.distinctPresent(container)).hasSize(17);
    }

    @Test
    void nibbleMode_mixedRandomWrites_roundTrip() {
        TestIdMap idMap = TestContainers.blockIdMap(16);
        OptimizedPalettedContainer<String> container =
                TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.applyRandomOps(container, 12345L, 5000, idMap, 16);
        assertThat(container.bitsPerEntry()).isEqualTo(4);

        assertRoundTrip(container, idMap);
        net.minecraft.world.level.chunk.PalettedContainer<String> copy = container.copy();
        TestContainers.assertCellsEqual(container, copy);
    }
}
