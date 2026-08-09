package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant and boundary tests for {@link OptimizedPalettedContainer}.
 */
class InvariantTest {

    private static final int BLOCKS = 4096;
    private static final int BIOMES = 64;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allAir_bitsZero_serializedAsBits0() {
        IdMap<String> idMap = TestIdMap.blockStates("air", "stone");
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container = new OptimizedPalettedContainer<>("air", strategy);

        assertThat(container.bitsPerEntry()).isZero();

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer);
        // First wire byte is the bits-per-entry; a uniform single-value container writes 0.
        assertThat(buffer.getByte(0)).isZero();
    }

    @Test
    void allSameValue_singleStorage_bitsZero() {
        IdMap<String> idMap = TestIdMap.blockStates("air", "stone");
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container = new OptimizedPalettedContainer<>("stone", strategy);

        assertThat(container.bitsPerEntry()).isZero();
        assertThat(container.get(0, 0, 0)).isEqualTo("stone");
        assertThat(container.get(15, 15, 15)).isEqualTo("stone");

        // Writing the same value again must not grow the storage.
        container.set(7, 7, 7, "stone");
        assertThat(container.bitsPerEntry()).isZero();
    }

    @Test
    void full4096Write_allCellsCorrect() {
        IdMap<String> idMap = TestIdMap.blockStates("air", "stone", "dirt");
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container = new OptimizedPalettedContainer<>("air", strategy);

        for (int index = 0; index < BLOCKS; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = (index >> 8) & 15;
            container.set(x, y, z, (index & 1) == 0 ? "stone" : "dirt");
        }

        for (int index = 0; index < BLOCKS; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = (index >> 8) & 15;
            assertThat(container.get(x, y, z)).isEqualTo((index & 1) == 0 ? "stone" : "dirt");
        }
    }

    @Test
    void index0_and_index4095_boundary() {
        // Block-state section: index 0 (min corner) and 4095 (max corner).
        IdMap<String> blockMap = TestIdMap.blockStates("air", "stone", "dirt");
        Strategy<String> blocksStrategy = Strategy.createForBlockStates(blockMap);
        OptimizedPalettedContainer<String> blocks = new OptimizedPalettedContainer<>("air", blocksStrategy);
        assertThat(blocksStrategy.getIndex(0, 0, 0)).isZero();
        assertThat(blocksStrategy.getIndex(15, 15, 15)).isEqualTo(BLOCKS - 1);

        blocks.set(0, 0, 0, "stone");
        blocks.set(15, 15, 15, "dirt");
        assertThat(blocks.get(0, 0, 0)).isEqualTo("stone");
        assertThat(blocks.get(15, 15, 15)).isEqualTo("dirt");

        // Biome section: index 0 and 63 (biomes are 4×4×4 = 64 cells).
        IdMap<String> biomeMap = TestIdMap.blockStates("plains", "desert", "ocean");
        Strategy<String> biomesStrategy = Strategy.createForBiomes(biomeMap);
        OptimizedPalettedContainer<String> biomes = new OptimizedPalettedContainer<>("plains", biomesStrategy);
        assertThat(biomesStrategy.getIndex(0, 0, 0)).isZero();
        assertThat(biomesStrategy.getIndex(3, 3, 3)).isEqualTo(BIOMES - 1);

        biomes.set(0, 0, 0, "desert");
        biomes.set(3, 3, 3, "ocean");
        assertThat(biomes.get(0, 0, 0)).isEqualTo("desert");
        assertThat(biomes.get(3, 3, 3)).isEqualTo("ocean");
    }

    @Test
    void entryCount_blocks4096_biomes64() {
        IdMap<String> blockMap = TestIdMap.blockStates("air", "stone");
        assertThat(Strategy.createForBlockStates(blockMap).entryCount()).isEqualTo(BLOCKS);

        IdMap<String> biomeMap = TestIdMap.blockStates("plains", "desert");
        assertThat(Strategy.createForBiomes(biomeMap).entryCount()).isEqualTo(BIOMES);
    }

    @Test
    void paletteSize_vs_bits_consistent() {
        String[] values = new String[64];
        for (int i = 0; i < values.length; i++) {
            values[i] = "v" + i;
        }
        IdMap<String> idMap = TestIdMap.blockStates(values);
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container = new OptimizedPalettedContainer<>("v0", strategy);

        // 1 distinct value → 0 bits (SingleStorage).
        assertThat(container.bitsPerEntry()).isZero();

        // 2 distinct values → 4 bits (block-state strategy floors small palettes at 4).
        container.set(0, 0, 0, "v1");
        assertThat(container.bitsPerEntry()).isEqualTo(4);

        // 3..16 distinct values → still 4 bits.
        for (int i = 2; i < 16; i++) {
            container.set(0, 0, i, "v" + i);
        }
        assertThat(container.bitsPerEntry()).isEqualTo(4);

        // 17 distinct values → 5 bits.
        container.set(1, 0, 0, "v16");
        assertThat(container.bitsPerEntry()).isEqualTo(5);

        // 33 distinct values → 6 bits.
        for (int i = 17; i < 33; i++) {
            container.set(0, 1, i - 17, "v" + i);
        }
        assertThat(container.bitsPerEntry()).isEqualTo(6);
    }

    @Test
    void counts_matchActualStates() {
        IdMap<String> idMap = TestIdMap.blockStates("air", "stone", "dirt", "water");
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container = new OptimizedPalettedContainer<>("air", strategy);

        for (int i = 0; i < 100; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            container.set(x, y, z, "stone");
        }
        for (int i = 100; i < 150; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            container.set(x, y, z, "dirt");
        }
        container.set(15, 15, 15, "water"); // cell 4095, previously air

        Map<String, Integer> counts = new HashMap<>();
        container.count((value, count) -> counts.merge(value, count, Integer::sum));

        Map<String, Integer> expected = new HashMap<>();
        expected.put("air", BLOCKS - 151);
        expected.put("stone", 100);
        expected.put("dirt", 50);
        expected.put("water", 1);
        assertThat(counts).isEqualTo(expected);
    }

    /**
     * The uniform-air fast path ({@link OptimizedPalettedContainer#isUniformAir()})
     * is true only while the whole section is a single air value. Writing a
     * non-air block moves the storage out of SingleStorage, after which the
     * container is never uniform air again — even if that cell is later reset
     * to air (the storage stays indirect).
     */
    @Test
    void uniformAir_trueForAirDefault_falseAfterNonAirWrite() {
        OptimizedPalettedContainer<BlockState> container = new OptimizedPalettedContainer<>(
                Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));

        assertThat(container.isUniformAir()).isTrue();

        container.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        assertThat(container.isUniformAir()).isFalse();

        // Back to air in the same cell: storage has already left SingleStorage.
        container.set(0, 0, 0, Blocks.AIR.defaultBlockState());
        assertThat(container.isUniformAir()).isFalse();
    }
}
