package com.github.uright008.nep;

import net.minecraft.world.level.chunk.PalettedContainer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage-transition tests (dim 2) for {@code OptimizedPalettedContainer}:
 * SingleStorage &rarr; IndirectStorage &rarr; GlobalStorage switches, the
 * {@code copyInto} resize path, and {@code getAndSet} old-value snapshots
 * across a resize. Where meaningful, the vanilla container is driven with the
 * same operations as an oracle.
 */
class StorageSwitchTest {

    private static PalettedContainer<String> newVanilla(TestIdMap idMap) {
        return TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
    }

    private static PalettedContainer<String> newNep(TestIdMap idMap) {
        return TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
    }

    @Test
    void singleToIndirect_onSecondValue() {
        TestIdMap idMap = TestContainers.blockIdMap(8);
        PalettedContainer<String> nep = newNep(idMap);
        PalettedContainer<String> vanilla = newVanilla(idMap);

        assertThat(nep.bitsPerEntry()).isZero(); // SingleStorage

        nep.set(0, 0, 0, idMap.byId(1));
        vanilla.set(0, 0, 0, idMap.byId(1));

        // Block-state sections use a 4-bit floor: 2 distinct values -> 4 bits.
        assertThat(nep.bitsPerEntry()).isEqualTo(4);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
        assertThat(nep.get(0, 0, 0)).isEqualTo(idMap.byId(1));
        assertThat(nep.get(15, 15, 15)).isEqualTo(TestContainers.defaultValue(idMap));
    }

    @Test
    void indirectToGlobal_at257Values() {
        TestIdMap idMap = TestContainers.blockIdMap(257);
        PalettedContainer<String> nep = newNep(idMap);
        PalettedContainer<String> vanilla = newVanilla(idMap);

        TestContainers.fillDistinct(nep, idMap, 256);
        assertThat(nep.bitsPerEntry()).isEqualTo(8); // largest indirect palette

        TestContainers.fillDistinct(vanilla, idMap, 256);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());

        // The 257th distinct value forces the switch to global storage.
        TestContainers.fillDistinct(nep, idMap, 257);
        TestContainers.fillDistinct(vanilla, idMap, 257);

        assertThat(nep.bitsPerEntry()).isEqualTo(9); // ceilLog2(257 registered values)
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
        TestContainers.assertCellsEqual(vanilla, nep);
    }

    @Test
    void copyInto_preservesAllCells_acrossResize() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> nep = newNep(idMap);
        PalettedContainer<String> vanilla = newVanilla(idMap);

        // 250 distinct values: still indirect storage.
        TestContainers.fillDistinct(nep, idMap, 250);
        TestContainers.fillDistinct(vanilla, idMap, 250);
        TestContainers.assertCellsEqual(vanilla, nep);

        // Push past 256 distinct values one at a time: each write re-encodes the
        // whole section through copyInto, so nothing may be lost.
        for (int i = 250; i < 260; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            nep.set(x, y, z, idMap.byId(i));
            vanilla.set(x, y, z, idMap.byId(i));
        }

        TestContainers.assertCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
        for (int i = 0; i < 260; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            assertThat(nep.get(x, y, z)).as("cell %d", i).isEqualTo(idMap.byId(i));
        }
    }

    @Test
    void getAndSet_snapshotTargetDoubleRead_onResize() {
        TestIdMap idMap = TestContainers.blockIdMap(4);
        PalettedContainer<String> nep = newNep(idMap);
        PalettedContainer<String> vanilla = newVanilla(idMap);

        // getAndSet with a brand-new value triggers SingleStorage -> IndirectStorage
        // mid-call; the returned old value must still be the pre-write cell value.
        String oldNep = nep.getAndSet(5, 6, 7, idMap.byId(1));
        String oldVanilla = vanilla.getAndSet(5, 6, 7, idMap.byId(1));

        assertThat(oldNep).isEqualTo(TestContainers.defaultValue(idMap));
        assertThat(oldNep).isEqualTo(oldVanilla);
        assertThat(nep.get(5, 6, 7)).isEqualTo(idMap.byId(1));
        TestContainers.assertCellsEqual(vanilla, nep);
    }

    @Test
    void smallPalette_reverseMapBuiltAt16() {
        TestIdMap idMap = TestContainers.blockIdMap(17);
        PalettedContainer<String> nep = newNep(idMap);
        PalettedContainer<String> vanilla = newVanilla(idMap);

        // 16 distinct values: the reverse map has not been built yet, but every
        // cell must still be readable (linear probe fallback).
        TestContainers.fillDistinct(nep, idMap, 16);
        TestContainers.fillDistinct(vanilla, idMap, 16);
        assertThat(nep.bitsPerEntry()).isEqualTo(4);
        TestContainers.assertCellsEqual(vanilla, nep);
        for (int i = 0; i < 16; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            assertThat(nep.get(x, y, z)).as("cell %d", i).isEqualTo(idMap.byId(i));
        }

        // The 17th distinct value crosses SMALL_PALETTE_SIZE and builds the
        // identity reverse map; all cells must still read back correctly.
        TestContainers.fillDistinct(nep, idMap, 17);
        TestContainers.fillDistinct(vanilla, idMap, 17);
        assertThat(nep.bitsPerEntry()).isEqualTo(5);
        TestContainers.assertCellsEqual(vanilla, nep);
        for (int i = 0; i < 17; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            assertThat(nep.get(x, y, z)).as("cell %d", i).isEqualTo(idMap.byId(i));
        }
    }

    @Test
    void singleStorage_setSameValue_doesNotResize() {
        TestIdMap idMap = TestContainers.blockIdMap(2);
        String defaultValue = TestContainers.defaultValue(idMap);
        PalettedContainer<String> nep = TestContainers.nepBlocks(defaultValue, idMap);

        nep.set(0, 0, 0, defaultValue);
        nep.set(15, 15, 15, defaultValue);
        nep.set(7, 8, 9, defaultValue);

        assertThat(nep.bitsPerEntry()).isZero(); // still SingleStorage
        assertThat(nep.get(0, 0, 0)).isEqualTo(defaultValue);

        // Vanilla agrees: re-setting the single value never resizes either.
        PalettedContainer<String> vanilla = TestContainers.vanillaBlocks(defaultValue, idMap);
        vanilla.set(0, 0, 0, defaultValue);
        vanilla.set(15, 15, 15, defaultValue);
        assertThat(vanilla.bitsPerEntry()).isZero();
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }

    @Test
    void biomes_singleToIndirect_onSecondValue() {
        TestIdMap idMap = TestContainers.biomeIdMap(8);
        PalettedContainer<String> nep = TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        PalettedContainer<String> vanilla = TestContainers.vanillaBiomes(TestContainers.defaultValue(idMap), idMap);

        assertThat(nep.bitsPerEntry()).isZero();

        nep.set(0, 0, 0, idMap.byId(1));
        vanilla.set(0, 0, 0, idMap.byId(1));

        // Biomes have no 4-bit floor (maxIndirectBits=3): 2 distinct values -> 1 bit.
        assertThat(nep.bitsPerEntry()).isEqualTo(1);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
        TestContainers.assertBiomeCellsEqual(vanilla, nep);
    }
}
