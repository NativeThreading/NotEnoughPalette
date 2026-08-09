package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@link BlockState} layer tests for {@link OptimizedPalettedContainer}.
 *
 * <p>These tests use the actual block-state registry
 * ({@link Block#BLOCK_STATE_REGISTRY}) and real block values
 * ({@link Blocks#STONE}, {@link Blocks#AIR}, ...), so they require a Minecraft
 * bootstrap ({@link SharedConstants#tryDetectVersion()} +
 * {@link Bootstrap#bootStrap()}) exactly like the NT reference test
 * {@code ExplosionRayFlatDifferentialTest}.</p>
 */
class BlockStateLevelTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Strategy<BlockState> blockStateStrategy() {
        return Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    }

    private static OptimizedPalettedContainer<BlockState> airContainer() {
        return new OptimizedPalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStateStrategy());
    }

    @Test
    void uniformAir_isUniformAir_true() {
        OptimizedPalettedContainer<BlockState> container = airContainer();

        assertThat(container.isUniformAir()).isTrue();
        assertThat(container.get(0, 0, 0)).isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(container.get(15, 15, 15)).isSameAs(Blocks.AIR.defaultBlockState());
    }

    @Test
    void isUniformAir_false_afterSingleNonAirWrite() {
        OptimizedPalettedContainer<BlockState> container = airContainer();
        container.set(7, 8, 9, Blocks.STONE.defaultBlockState());

        assertThat(container.isUniformAir()).isFalse();
        assertThat(container.get(7, 8, 9)).isSameAs(Blocks.STONE.defaultBlockState());
    }

    @Test
    void blocksContainer_entryCount4096() {
        assertThat(blockStateStrategy().entryCount()).isEqualTo(4096);
    }

    @Test
    void realBlock_roundTrip_serialization() {
        OptimizedPalettedContainer<BlockState> original = airContainer();
        original.set(2, 3, 4, Blocks.STONE.defaultBlockState());
        original.set(5, 6, 7, Blocks.DIRT.defaultBlockState());
        original.set(15, 15, 15, Blocks.WATER.defaultBlockState());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buffer);

        OptimizedPalettedContainer<BlockState> restored = airContainer();
        buffer.readerIndex(0);
        restored.read(buffer);

        assertThat(restored.get(2, 3, 4)).isSameAs(Blocks.STONE.defaultBlockState());
        assertThat(restored.get(5, 6, 7)).isSameAs(Blocks.DIRT.defaultBlockState());
        assertThat(restored.get(15, 15, 15)).isSameAs(Blocks.WATER.defaultBlockState());
        assertThat(restored.get(0, 0, 0)).isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(restored.bitsPerEntry()).isEqualTo(original.bitsPerEntry());
        assertThat(restored.isUniformAir()).isFalse();
    }

    @Test
    void crossImplRealBlocks_nepWrite_vanillaRead() {
        OptimizedPalettedContainer<BlockState> nep = airContainer();
        nep.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        nep.set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        nep.set(15, 15, 15, Blocks.WATER.defaultBlockState());
        nep.set(8, 8, 8, Blocks.OAK_PLANKS.defaultBlockState());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        nep.write(buffer);

        PalettedContainer<BlockState> vanilla =
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStateStrategy());
        buffer.readerIndex(0);
        vanilla.read(buffer);

        for (int index = 0; index < 4096; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = (index >> 8) & 15;
            assertThat(vanilla.get(x, y, z)).isSameAs(nep.get(x, y, z));
        }
    }

    @Test
    void uniformAirFastPath_noBootstrapRead() {
        // isUniformAir() is an O(1) fast path: it inspects only the single stored
        // value (no per-cell storage walk, no registry lookup). Explicit air
        // writes must keep the storage in SingleStorage so the fast path holds.
        OptimizedPalettedContainer<BlockState> container = airContainer();
        assertThat(container.isUniformAir()).isTrue();
        assertThat(container.bitsPerEntry()).isZero();

        container.set(3, 4, 5, Blocks.AIR.defaultBlockState());
        container.set(11, 2, 9, Blocks.AIR.defaultBlockState());

        assertThat(container.bitsPerEntry()).isZero(); // still SingleStorage
        assertThat(container.isUniformAir()).isTrue();
    }
}
