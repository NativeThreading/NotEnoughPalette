package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression for the biome deserialization crash: IndirectStorage(int[], List)
 * used ids.length (the shared 4096 TO_INT_BUF) as the entry count, so a 64-cell
 * biome section was treated as 4096 cells. When TO_INT_BUF held leftover junk
 * >= the palette size, getAll() walked out of bounds and biome decoration
 * (applyBiomeDecoration) crashed with ArrayIndexOutOfBoundsException.
 *
 * The test poisons TO_INT_BUF so the pre-fix code deterministically copies
 * an out-of-range id into the nibble storage, reproducing the crash.
 */
class BiomeGetAllReproTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void biome_getAll_afterRead_doesNotCrashEvenWithDirtyBuffer() throws Exception {
        for (int distinct : new int[]{2, 4, 5, 8}) {
            TestIdMap idMap = TestContainers.biomeIdMap(64);
            OptimizedPalettedContainer<String> container =
                    TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
            TestContainers.fillDistinctBiomes(container, idMap, distinct);

            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            container.write(buffer);

            // Poison the shared TO_INT_BUF with an out-of-range id in the tail.
            Field field = OptimizedPalettedContainer.class.getDeclaredField("TO_INT_BUF");
            field.setAccessible(true);
            ThreadLocal<int[]> buf = (ThreadLocal<int[]>) field.get(null);
            int[] dirty = buf.get();
            for (int i = 64; i < dirty.length; i++) {
                dirty[i] = 0xFFFF; // out of any palette range
            }

            OptimizedPalettedContainer<String> restored =
                    TestContainers.nepBiomes(TestContainers.defaultValue(idMap), idMap);
            buffer.readerIndex(0);
            restored.read(buffer);

            List<String> values = new ArrayList<>();
            try {
                restored.getAll(values::add);
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new AssertionError("getAll crash after read at distinct=" + distinct
                        + " bits=" + restored.bitsPerEntry(), e);
            }
            org.assertj.core.api.Assertions.assertThat(values).hasSize(distinct);
        }
    }
}
