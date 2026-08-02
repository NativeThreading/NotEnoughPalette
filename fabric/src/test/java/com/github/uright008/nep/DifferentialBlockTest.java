package com.github.uright008.nep;

import net.minecraft.world.level.chunk.PalettedContainer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * String-level differential tests (dim 1: value identity). Each test drives the
 * vanilla {@link PalettedContainer} and {@code OptimizedPalettedContainer} with
 * the exact same {@link TestIdMap} and operation sequence, then asserts the NEP
 * container's observable state matches the vanilla container cell-by-cell.
 *
 * <p>This is a characterization suite: the NEP implementation already exists, so
 * a failing assertion here would indicate a genuine NEP bug (to be recorded,
 * not fixed in this wave).</p>
 */
class DifferentialBlockTest {

    private static PalettedContainer<String> newVanilla(TestIdMap idMap) {
        return TestContainers.vanillaBlocks(TestContainers.defaultValue(idMap), idMap);
    }

    private static PalettedContainer<String> newNep(TestIdMap idMap) {
        return TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
    }

    private static List<String> collectAll(PalettedContainer<String> container) {
        List<String> values = new ArrayList<>();
        container.getAll(values::add);
        return values;
    }

    private static Map<String, Integer> count(PalettedContainer<String> container) {
        Map<String, Integer> counts = new HashMap<>();
        container.count((value, amount) -> counts.put(value, amount));
        return counts;
    }

    private static List<String> paletteValues(PalettedContainer<String> container) {
        List<String> values = new ArrayList<>();
        container.forEachInPalette(values::add);
        return values;
    }

    @Test
    void identicalValues_afterRandomSequence_staysSingle() {
        TestIdMap idMap = TestContainers.blockIdMap(2);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 1L, 500, idMap, 2);
        TestContainers.applyRandomOps(nep, 1L, 500, idMap, 2);

        TestContainers.assertCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }

    @Test
    void identicalValues_afterRandomSequence_indirect() {
        TestIdMap idMap = TestContainers.blockIdMap(100);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 5L, 2000, idMap, 64);
        TestContainers.applyRandomOps(nep, 5L, 2000, idMap, 64);

        TestContainers.assertCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }

    @Test
    void identicalValues_afterRandomSequence_forcedGlobal() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        // Force 300 distinct values (> 256) so both containers leave indirect storage.
        TestContainers.fillDistinct(vanilla, idMap, 300);
        TestContainers.fillDistinct(nep, idMap, 300);
        TestContainers.applyRandomOps(vanilla, 77L, 2000, idMap, 300);
        TestContainers.applyRandomOps(nep, 77L, 2000, idMap, 300);

        TestContainers.assertCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }

    @Test
    void identicalValues_everyDistinctCount_1To300() {
        int[] sizes = {1, 2, 17, 257};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            PalettedContainer<String> vanilla = newVanilla(idMap);
            PalettedContainer<String> nep = newNep(idMap);

            TestContainers.fillDistinct(vanilla, idMap, size);
            TestContainers.fillDistinct(nep, idMap, size);

            assertThat(nep.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            TestContainers.assertCellsEqual(vanilla, nep);
        }
    }

    @Test
    void getAndSet_returnsOldValue_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        // Small palette first, then grow through every storage regime via getAndSet
        // (the first introduction of each value 3..299 triggers a resize).
        TestContainers.applyRandomOps(vanilla, 55L, 500, idMap, 3);
        TestContainers.applyRandomOps(nep, 55L, 500, idMap, 3);

        Random random = new Random(99L);
        for (int index = 0; index < 4096; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = (index >> 8) & 15;
            String value = idMap.byId(random.nextInt(300));
            String oldVanilla = vanilla.getAndSet(x, y, z, value);
            String oldNep = nep.getAndSet(x, y, z, value);
            assertThat(oldNep).as("old value at index %d", index).isEqualTo(oldVanilla);
        }

        TestContainers.assertCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }

    @Test
    void getAll_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 21L, 3000, idMap, 300);
        TestContainers.applyRandomOps(nep, 21L, 3000, idMap, 300);

        List<String> vanillaAll = collectAll(vanilla);
        List<String> nepAll = collectAll(nep);
        assertThat(nepAll).containsExactlyInAnyOrderElementsOf(vanillaAll);
        // getAll must be exact: the distinct values actually present in the cells.
        assertThat(new HashSet<>(nepAll)).isEqualTo(TestContainers.distinctPresent(nep));
    }

    @Test
    void count_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 23L, 3000, idMap, 300);
        TestContainers.applyRandomOps(nep, 23L, 3000, idMap, 300);

        Map<String, Integer> vanillaCounts = count(vanilla);
        Map<String, Integer> nepCounts = count(nep);
        assertThat(nepCounts).isEqualTo(vanillaCounts);
        int total = nepCounts.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(4096);
    }

    @Test
    void maybeHas_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(100);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        // Only values 0..9 are ever set; values 10..99 stay registered but absent.
        TestContainers.applyRandomOps(vanilla, 7L, 1000, idMap, 10);
        TestContainers.applyRandomOps(nep, 7L, 1000, idMap, 10);

        for (String value : TestContainers.distinctPresent(nep)) {
            assertThat(nep.maybeHas(v -> v.equals(value))).as("present %s", value).isTrue();
            assertThat(vanilla.maybeHas(v -> v.equals(value))).isTrue();
        }
        for (int i = 10; i < 20; i++) {
            String absent = idMap.byId(i);
            boolean nepHas = nep.maybeHas(v -> v.equals(absent));
            boolean vanillaHas = vanilla.maybeHas(v -> v.equals(absent));
            assertThat(nepHas).as("absent %s", absent).isEqualTo(vanillaHas).isFalse();
        }
    }

    @Test
    void forEachInPalette_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(64);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 9L, 2000, idMap, 64);
        TestContainers.applyRandomOps(nep, 9L, 2000, idMap, 64);

        List<String> vanillaPalette = paletteValues(vanilla);
        List<String> nepPalette = paletteValues(nep);
        assertThat(nepPalette).containsExactlyInAnyOrderElementsOf(vanillaPalette);
    }

    @Test
    void copy_isIndependent_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 101L, 2000, idMap, 100);
        TestContainers.applyRandomOps(nep, 101L, 2000, idMap, 100);

        PalettedContainer<String> vanillaCopy = vanilla.copy();
        PalettedContainer<String> nepCopy = nep.copy();

        // A copy snapshots the original state.
        TestContainers.assertCellsEqual(vanilla, vanillaCopy);
        TestContainers.assertCellsEqual(nep, nepCopy);
        assertThat(nepCopy.bitsPerEntry()).isEqualTo(nep.bitsPerEntry());

        // Mutating the copies must not disturb the originals. If the NEP copy
        // aliased its source storage, the original would drift from vanilla's.
        TestContainers.applyRandomOps(vanillaCopy, 202L, 1000, idMap, 300);
        TestContainers.applyRandomOps(nepCopy, 202L, 1000, idMap, 300);

        TestContainers.assertCellsEqual(vanilla, nep);
        TestContainers.assertCellsEqual(vanillaCopy, nepCopy);
        assertThat(nepCopy.bitsPerEntry()).isEqualTo(vanillaCopy.bitsPerEntry());
    }

    @Test
    void recreate_matchesVanilla() {
        TestIdMap idMap = TestContainers.blockIdMap(64);
        PalettedContainer<String> vanilla = newVanilla(idMap);
        PalettedContainer<String> nep = newNep(idMap);

        TestContainers.applyRandomOps(vanilla, 31L, 2000, idMap, 64);
        TestContainers.applyRandomOps(nep, 31L, 2000, idMap, 64);

        PalettedContainer<String> vanillaRecreated = vanilla.recreate();
        PalettedContainer<String> nepRecreated = nep.recreate();

        assertThat(nepRecreated.bitsPerEntry()).isZero();
        assertThat(vanillaRecreated.bitsPerEntry()).isZero();
        TestContainers.assertCellsEqual(vanillaRecreated, nepRecreated);

        String defaultValue = TestContainers.defaultValue(idMap);
        for (int y = 0; y < TestContainers.BLOCK_AXIS; y++) {
            for (int z = 0; z < TestContainers.BLOCK_AXIS; z++) {
                for (int x = 0; x < TestContainers.BLOCK_AXIS; x++) {
                    assertThat(nepRecreated.get(x, y, z)).isEqualTo(defaultValue);
                }
            }
        }
    }

    @Test
    void bitsPerEntry_matchesVanillaAtEverySize() {
        int[] sizes = {1, 2, 16, 17, 32, 256, 257};
        for (int size : sizes) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            PalettedContainer<String> vanilla = newVanilla(idMap);
            PalettedContainer<String> nep = newNep(idMap);

            TestContainers.fillDistinct(vanilla, idMap, size);
            TestContainers.fillDistinct(nep, idMap, size);

            assertThat(nep.bitsPerEntry()).as("bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            TestContainers.assertCellsEqual(vanilla, nep);
        }
    }

    @Test
    void biomes_entryCount64_differential() {
        TestIdMap idMap = TestContainers.biomeIdMap(64);

        int[] sizes = {1, 2, 8, 9, 17, 64};
        for (int size : sizes) {
            PalettedContainer<String> vanilla =
                    TestContainers.vanillaBiomes(TestContainers.defaultValue(idMap), idMap);
            PalettedContainer<String> nep =
                    TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);

            TestContainers.fillDistinctBiomes(vanilla, idMap, size);
            TestContainers.fillDistinctBiomes(nep, idMap, size);

            assertThat(nep.bitsPerEntry()).as("biome bits at %d distinct", size).isEqualTo(vanilla.bitsPerEntry());
            TestContainers.assertBiomeCellsEqual(vanilla, nep);
        }

        PalettedContainer<String> vanilla =
                TestContainers.vanillaBiomes(TestContainers.defaultValue(idMap), idMap);
        PalettedContainer<String> nep =
                TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
        TestContainers.applyRandomBiomeOps(vanilla, 3L, 2000, idMap, 32);
        TestContainers.applyRandomBiomeOps(nep, 3L, 2000, idMap, 32);

        TestContainers.assertBiomeCellsEqual(vanilla, nep);
        assertThat(nep.bitsPerEntry()).isEqualTo(vanilla.bitsPerEntry());
    }
}
