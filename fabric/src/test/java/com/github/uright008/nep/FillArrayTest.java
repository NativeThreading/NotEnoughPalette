package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fillArray() must bulk-unpack every cell in section-index order, identical to
 * per-cell get(x,y,z), across every storage mode (Single / nibble / byte /
 * CharGlobal). This is the backing of Sodium's sodium$unpack fast path.
 */
class FillArrayTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void assertFillMatchesGet(OptimizedPalettedContainer<String> container, TestIdMap idMap) {
        String[] out = new String[4096];
        container.fillArray(out);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    assertThat(out[index])
                            .as("cell (%d,%d,%d) index=%d", x, y, z, index)
                            .isSameAs(container.get(x, y, z));
                }
            }
        }
    }

    @Test
    void fillArray_singleMode_matchesGet() {
        TestIdMap idMap = TestContainers.blockIdMap(4);
        OptimizedPalettedContainer<String> c = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        assertFillMatchesGet(c, idMap);
    }

    @Test
    void fillArray_nibbleAndByte_matchesGet() {
        for (int distinct : new int[]{2, 16, 17, 100, 200}) {
            TestIdMap idMap = TestContainers.blockIdMap(300);
            OptimizedPalettedContainer<String> c = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinct(c, idMap, distinct);
            assertFillMatchesGet(c, idMap);
        }
    }

    @Test
    void fillArray_global_matchesGet() {
        TestIdMap idMap = TestContainers.blockIdMap(300);
        OptimizedPalettedContainer<String> c = TestContainers.nepBlocks(TestContainers.defaultValue(idMap), idMap);
        TestContainers.fillDistinct(c, idMap, 300); // > 256 -> global
        assertThat(c.bitsPerEntry()).isGreaterThan(8);
        assertFillMatchesGet(c, idMap);
    }
}
