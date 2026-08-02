package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * In-game integration tests for the {@link OptimizedPalettedContainer} swap.
 *
 * <p>NEP replaces vanilla {@code PalettedContainer}s through two mixins:
 * {@code PalettedContainerFactoryMixin} intercepts the factory creation methods
 * and {@code PalettedContainerMixin} intercepts {@code unpack}. These tests
 * verify against a live {@link ServerLevel}: that real chunk sections actually
 * hold optimized containers, that placing/reading blocks round-trips, that
 * section serialization is lossless, and that per-cell {@code getBlockState}
 * lookups (the NT explosion-pipeline access pattern) agree with the expected
 * pattern.</p>
 *
 * <p>Palette sizes are deliberately kept so every section stays at 16 or fewer
 * distinct states (bits = 4, a divisor of 64). NEP {@code packBits} currently
 * mis-sizes its backing array for bit counts that do not divide 64 (see
 * {@code nepWrite_bitsNotDividing64_knownBug_throwsAIOOBE}), and world sections
 * start with a few terrain states; a larger test palette would push the union
 * past 16 entries and crash chunk saving.</p>
 */
public final class PalettedContainerGameTest {

    /** 16 distinct states (15 solid + air): exercises the reverse-map transition at exactly 16 entries. */
    private static final BlockState[] PALETTE = {
        Blocks.STONE.defaultBlockState(),
        Blocks.GRANITE.defaultBlockState(),
        Blocks.DIORITE.defaultBlockState(),
        Blocks.ANDESITE.defaultBlockState(),
        Blocks.DIRT.defaultBlockState(),
        Blocks.COARSE_DIRT.defaultBlockState(),
        Blocks.SAND.defaultBlockState(),
        Blocks.RED_SAND.defaultBlockState(),
        Blocks.GRAVEL.defaultBlockState(),
        Blocks.COBBLESTONE.defaultBlockState(),
        Blocks.OAK_PLANKS.defaultBlockState(),
        Blocks.SPRUCE_PLANKS.defaultBlockState(),
        Blocks.BIRCH_PLANKS.defaultBlockState(),
        Blocks.OAK_LOG.defaultBlockState(),
        Blocks.OBSIDIAN.defaultBlockState(),
        Blocks.AIR.defaultBlockState()
    };

    /** Small palette for world fills: 9 + any pre-existing terrain stays ≤ 16 (bits 4). */
    private static final BlockState[] FILL_PALETTE = {
        Blocks.STONE.defaultBlockState(),
        Blocks.DIRT.defaultBlockState(),
        Blocks.SAND.defaultBlockState(),
        Blocks.GRAVEL.defaultBlockState(),
        Blocks.OAK_PLANKS.defaultBlockState(),
        Blocks.COBBLESTONE.defaultBlockState(),
        Blocks.OAK_LOG.defaultBlockState(),
        Blocks.OBSIDIAN.defaultBlockState(),
        Blocks.AIR.defaultBlockState()
    };

    @GameTest(maxTicks = 20, padding = 48)
    public void factoryCreatesOptimizedContainer(GameTestHelper helper) {
        // Force-load the section through the world path so it is created by the
        // (mixined) PalettedContainerFactory.
        BlockPos placed = new BlockPos(8, 8, 8);
        helper.setBlock(placed, Blocks.STONE);

        LevelChunkSection section = sectionAt(helper, placed);
        helper.assertTrue(section.getStates() instanceof OptimizedPalettedContainer<?>,
                "LevelChunkSection.states must be an OptimizedPalettedContainer, got "
                        + section.getStates().getClass().getSimpleName());
        helper.assertTrue(section.getBiomes() instanceof OptimizedPalettedContainer<?>,
                "LevelChunkSection.biomes must be an OptimizedPalettedContainer, got "
                        + section.getBiomes().getClass().getSimpleName());
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void sectionPlaceBreak_parity(GameTestHelper helper) {
        BlockPos stone = new BlockPos(4, 4, 4);
        BlockPos dirt = stone.offset(1, 0, 0);
        BlockPos planks = stone.offset(2, 0, 0);
        BlockPos broken = stone.offset(1, 0, 1);

        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(dirt, Blocks.DIRT);
        helper.setBlock(planks, Blocks.OAK_PLANKS);
        helper.setBlock(broken, Blocks.SAND);
        helper.setBlock(broken, Blocks.AIR); // break

        helper.assertTrue(helper.getBlockState(stone).getBlock() == Blocks.STONE,
                "stone did not survive place/read");
        helper.assertTrue(helper.getBlockState(dirt).getBlock() == Blocks.DIRT,
                "dirt did not survive place/read");
        helper.assertTrue(helper.getBlockState(planks).getBlock() == Blocks.OAK_PLANKS,
                "oak planks did not survive place/read");
        helper.assertTrue(helper.getBlockState(broken).isAir(),
                "broken block must read back as air");

        assertSectionContains(helper, stone, Blocks.STONE, "stone");
        assertSectionContains(helper, dirt, Blocks.DIRT, "dirt");
        assertSectionContains(helper, planks, Blocks.OAK_PLANKS, "planks");
        assertSectionContains(helper, broken, Blocks.AIR, "broken");
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void sectionSaveLoad_roundTrip(GameTestHelper helper) {
        // Fresh factory-created sections: the palette is exactly PALETTE (16
        // entries, bits 4), so NEP write stays in the bit counts that divide 64.
        LevelChunkSection original = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        helper.assertTrue(original.getStates() instanceof OptimizedPalettedContainer<?>,
                "precondition failed: factory-created states are not optimized");

        fill(original, PALETTE);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buffer);

        LevelChunkSection restored = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        buffer.readerIndex(0);
        restored.read(buffer);

        helper.assertTrue(restored.getStates() instanceof OptimizedPalettedContainer<?>,
                "read path must keep section states optimized");
        helper.assertTrue(restored.hasOnlyAir() == original.hasOnlyAir(),
                "hasOnlyAir parity broken after save/load");
        helper.assertTrue(restored.getStates().bitsPerEntry() == original.getStates().bitsPerEntry(),
                "bitsPerEntry mismatch after save/load");

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState expected = original.getBlockState(x, y, z);
                    BlockState actual = restored.getBlockState(x, y, z);
                    if (expected != actual) {
                        helper.fail("save/load mismatch at (" + x + "," + y + "," + z + "): "
                                + expected + " != " + actual);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void fillSectioned_getBlockState_parity(GameTestHelper helper) {
        BlockPos anchor = new BlockPos(4, 4, 4);
        helper.setBlock(anchor, Blocks.STONE);
        LevelChunkSection section = sectionAt(helper, anchor);
        BlockPos base = sectionBase(helper, anchor);

        fill(section, FILL_PALETTE);

        // NT's explosion pipeline reads the same cells through both the world
        // lookup and the section container; both must agree with the pattern.
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState expected = paletteAt(cellIndex(x, y, z), FILL_PALETTE);
                    BlockState viaWorld = helper.getLevel().getBlockState(base.offset(x, y, z));
                    BlockState viaSection = section.getBlockState(x, y, z);
                    if (viaWorld != expected || viaSection != expected) {
                        helper.fail("sectioned lookup mismatch at (" + x + "," + y + "," + z + "): "
                                + "expected=" + expected + " viaWorld=" + viaWorld + " viaSection=" + viaSection);
                    }
                }
            }
        }
        helper.succeed();
    }

    private static void fill(LevelChunkSection section, BlockState[] palette) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.setBlockState(x, y, z, paletteAt(cellIndex(x, y, z), palette));
                }
            }
        }
    }

    private static int cellIndex(int x, int y, int z) {
        return x + (y << 4) + (z << 8);
    }

    private static BlockState paletteAt(int index, BlockState[] palette) {
        return palette[index % palette.length];
    }

    private static void assertSectionContains(GameTestHelper helper, BlockPos relative,
            Block expected, String label) {
        BlockPos abs = helper.absolutePos(relative);
        LevelChunk chunk = helper.getLevel().getChunk(abs.getX() >> 4, abs.getZ() >> 4);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(abs.getY()));
        BlockState actual = section.getBlockState(abs.getX() & 15, abs.getY() & 15, abs.getZ() & 15);
        helper.assertTrue(actual.getBlock() == expected,
                "section read-back mismatch at " + label + ": " + actual);
    }

    private static LevelChunkSection sectionAt(GameTestHelper helper, BlockPos relative) {
        BlockPos abs = helper.absolutePos(relative);
        LevelChunk chunk = helper.getLevel().getChunk(abs.getX() >> 4, abs.getZ() >> 4);
        return chunk.getSection(chunk.getSectionIndex(abs.getY()));
    }

    private static BlockPos sectionBase(GameTestHelper helper, BlockPos relative) {
        BlockPos abs = helper.absolutePos(relative);
        return new BlockPos(
                SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(abs.getX())),
                SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(abs.getY())),
                SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(abs.getZ())));
    }
}
