package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for String-level differential tests between the vanilla
 * {@link PalettedContainer} and {@link OptimizedPalettedContainer}.
 *
 * <p>Both implementations use identity-based ({@code ==}) palette lookups, so
 * every distinct value must be a distinct {@link String} object with distinct
 * content ({@code "v0".."vN-1"}). Callers must always fetch values through the
 * shared {@link TestIdMap} ({@link #defaultValue} / {@link TestIdMap#byId}) so
 * both containers receive the exact same object references.</p>
 */
final class TestContainers {

    static final int BLOCK_AXIS = 16;
    static final int BIOME_AXIS = 4;

    private TestContainers() {
    }

    /** {@code n} distinct String values {@code "v0".."vN-1"} (distinct objects, distinct content). */
    static List<String> distinctValues(int count) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add("v" + i);
        }
        return values;
    }

    /** IdMap with exactly {@code maxDistinct} pre-registered distinct values. */
    static TestIdMap blockIdMap(int maxDistinct) {
        return new TestIdMap(distinctValues(maxDistinct).toArray(new String[0]));
    }

    /** Biome container uses a 4x4x4 section but the same value registry semantics. */
    static TestIdMap biomeIdMap(int maxDistinct) {
        return blockIdMap(maxDistinct);
    }

    /** The canonical default value (id 0) — the same object for both containers. */
    static String defaultValue(TestIdMap idMap) {
        return idMap.byId(0);
    }

    static PalettedContainer<String> vanillaBlocks(String defaultValue, TestIdMap idMap) {
        return new PalettedContainer<>(defaultValue, Strategy.createForBlockStates(idMap));
    }

    static OptimizedPalettedContainer<String> nepBlocks(String defaultValue, TestIdMap idMap) {
        return new OptimizedPalettedContainer<>(defaultValue, Strategy.createForBlockStates(idMap));
    }

    static PalettedContainer<String> vanillaBiomes(String defaultValue, TestIdMap idMap) {
        return new PalettedContainer<>(defaultValue, Strategy.createForBiomes(idMap));
    }

    static OptimizedPalettedContainer<String> nepBiomes(String defaultValue, TestIdMap idMap) {
        return new OptimizedPalettedContainer<>(defaultValue, Strategy.createForBiomes(idMap));
    }

    /** Deterministic random set() ops over a 16x16x16 block section. */
    static void applyRandomOps(PalettedContainer<String> container, long seed, int ops, TestIdMap idMap, int maxDistinct) {
        applyRandomOps(container, seed, ops, idMap, maxDistinct, BLOCK_AXIS);
    }

    /** Deterministic random set() ops over a 4x4x4 biome section. */
    static void applyRandomBiomeOps(PalettedContainer<String> container, long seed, int ops, TestIdMap idMap, int maxDistinct) {
        applyRandomOps(container, seed, ops, idMap, maxDistinct, BIOME_AXIS);
    }

    private static void applyRandomOps(PalettedContainer<String> container, long seed, int ops, TestIdMap idMap, int maxDistinct, int axis) {
        Random random = new Random(seed);
        for (int i = 0; i < ops; i++) {
            int x = random.nextInt(axis);
            int y = random.nextInt(axis);
            int z = random.nextInt(axis);
            container.set(x, y, z, idMap.byId(random.nextInt(maxDistinct)));
        }
    }

    /** Sets cells {@code 0..n-1} to values {@code byId(0)..byId(n-1)} (n distinct values). */
    static void fillDistinct(PalettedContainer<String> container, TestIdMap idMap, int n) {
        fillDistinct(container, idMap, n, BLOCK_AXIS);
    }

    /** Same as {@link #fillDistinct} but on the 4x4x4 biome grid. */
    static void fillDistinctBiomes(PalettedContainer<String> container, TestIdMap idMap, int n) {
        fillDistinct(container, idMap, n, BIOME_AXIS);
    }

    private static void fillDistinct(PalettedContainer<String> container, TestIdMap idMap, int n, int axis) {
        int axisBits = Integer.numberOfTrailingZeros(axis);
        int mask = axis - 1;
        for (int i = 0; i < n; i++) {
            int x = i & mask;
            int z = (i >> axisBits) & mask;
            int y = (i >> (2 * axisBits)) & mask;
            container.set(x, y, z, idMap.byId(i));
        }
    }

    /** Asserts every cell of {@code actual} equals the corresponding cell of {@code expected} (block grid). */
    static void assertCellsEqual(PalettedContainer<String> expected, PalettedContainer<String> actual) {
        assertCellsEqual(expected, actual, BLOCK_AXIS);
    }

    /** Same as {@link #assertCellsEqual} but on the 4x4x4 biome grid. */
    static void assertBiomeCellsEqual(PalettedContainer<String> expected, PalettedContainer<String> actual) {
        assertCellsEqual(expected, actual, BIOME_AXIS);
    }

    private static void assertCellsEqual(PalettedContainer<String> expected, PalettedContainer<String> actual, int axis) {
        for (int y = 0; y < axis; y++) {
            for (int z = 0; z < axis; z++) {
                for (int x = 0; x < axis; x++) {
                    assertThat(actual.get(x, y, z)).isEqualTo(expected.get(x, y, z));
                }
            }
        }
    }

    /** Distinct values actually present in the block-grid cells (full scan via get). */
    static Set<String> distinctPresent(PalettedContainer<String> container) {
        Set<String> present = new HashSet<>();
        for (int y = 0; y < BLOCK_AXIS; y++) {
            for (int z = 0; z < BLOCK_AXIS; z++) {
                for (int x = 0; x < BLOCK_AXIS; x++) {
                    present.add(container.get(x, y, z));
                }
            }
        }
        return present;
    }
}
