package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

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
 * <p>The save/load palette is deliberately kept at 64 distinct states so it
 * exercises a wire bit count (6) that does not divide 64 — the regime NEP's
 * {@code packBits} previously mis-sized and crashed on during chunk saving.</p>
 */
public final class PalettedContainerGameTest {

    /** 64 distinct states (63 solid + air): exercises a non-64-dividing wire bit count (6) on a real section. */
    private static final BlockState[] PALETTE = concat(
            defaultStates(List.of(
                    Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
                    Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.SAND, Blocks.RED_SAND,
                    Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS,
                    Blocks.BIRCH_PLANKS, Blocks.OAK_LOG, Blocks.OBSIDIAN, Blocks.AIR)),
            defaultStates(Blocks.WOOL.asList()),
            defaultStates(Blocks.CONCRETE.asList()),
            defaultStates(Blocks.DYED_TERRACOTTA.asList()));

    private static BlockState[] concat(BlockState[]... groups) {
        int total = 0;
        for (BlockState[] group : groups) {
            total += group.length;
        }
        BlockState[] out = new BlockState[total];
        int offset = 0;
        for (BlockState[] group : groups) {
            System.arraycopy(group, 0, out, offset, group.length);
            offset += group.length;
        }
        return out;
    }

    private static BlockState[] defaultStates(List<Block> blocks) {
        BlockState[] states = new BlockState[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            states[i] = blocks.get(i).defaultBlockState();
        }
        return states;
    }

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

    /**
     * >256 distinct real BlockStates, collected programmatically from the 26.2
     * block registry plus the multi-block ColorCollections. Pushes a section
     * into global (CharGlobalStorage) storage — the regime gametest previously
     * never exercised.
     */
    private static BlockState[] globalPalette() {
        Set<BlockState> states = new HashSet<>();
        // Every distinct default state in the whole block registry (26.2 has 852 blocks).
        for (Block block : BuiltInRegistries.BLOCK) {
            states.add(block.defaultBlockState());
        }
        // Guarantee we're well past 256 even if registry iteration is trimmed by the test world.
        List<Block> extra = new ArrayList<>();
        extra.addAll(Blocks.WOOL.asList());
        extra.addAll(Blocks.CONCRETE.asList());
        extra.addAll(Blocks.DYED_TERRACOTTA.asList());
        extra.addAll(Blocks.STAINED_GLASS.asList());
        extra.addAll(Blocks.CARPET.asList());
        extra.addAll(Blocks.GLAZED_TERRACOTTA.asList());
        for (Block block : extra) {
            states.add(block.defaultBlockState());
        }
        return states.toArray(new BlockState[0]);
    }

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
        // Fresh factory-created sections: the palette is exactly PALETTE (64
        // entries, bits 6), so NEP write exercises a bit count that does not
        // divide 64 — the regime packBits used to mis-size on chunk saving.
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

    @GameTest(maxTicks = 20, padding = 48)
    public void globalStorage_roundTrip(GameTestHelper helper) {
        BlockState[] palette = globalPalette();
        helper.assertTrue(palette.length > 256,
                "global palette must exceed 256 distinct states to force global storage, got " + palette.length);

        LevelChunkSection original = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        fill(original, palette);

        // >256 states must have left byte indirect storage and entered global.
        helper.assertTrue(original.getStates().bitsPerEntry() > 8,
                "expected global bit width (>8), got " + original.getStates().bitsPerEntry());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buffer);

        LevelChunkSection restored = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        buffer.readerIndex(0);
        restored.read(buffer);

        helper.assertTrue(restored.getStates() instanceof OptimizedPalettedContainer<?>,
                "read path must keep section states optimized");
        helper.assertTrue(restored.getStates().bitsPerEntry() == original.getStates().bitsPerEntry(),
                "bitsPerEntry mismatch after save/load");
        assertSectionsEqual(helper, original, restored, "global");
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void nibbleToByteUpgrade_16To17(GameTestHelper helper) {
        // Use 15 distinct states: with the section's implicit air that is exactly
        // 16 palette entries (the nibble boundary). A 16th distinct state added
        // afterward must cross into byte mode.
        BlockState[] base15 = new BlockState[15];
        for (int i = 0; i < 15; i++) {
            base15[i] = Blocks.WOOL.asList().get(i).defaultBlockState();
        }

        LevelChunkSection section = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        // Fill cells 0..14 with 15 distinct states (nibble mode, bits 4 once air joins).
        for (int i = 0; i < 15; i++) {
            section.setBlockState(i, 0, 0, base15[i]);
        }
        helper.assertTrue(section.getStates().bitsPerEntry() == 4,
                "15 states + air should stay in 4-bit nibble mode, got " + section.getStates().bitsPerEntry());

        // Add a 16th distinct state -> palette 17 -> nibble -> byte expansion.
        section.setBlockState(15, 0, 0, Blocks.DIAMOND_BLOCK.defaultBlockState());
        helper.assertTrue(section.getStates().bitsPerEntry() >= 5,
                "16th distinct state should expand to byte mode (>=5 bits), got "
                        + section.getStates().bitsPerEntry());

        // Every cell must read back correctly after the expansion.
        for (int i = 0; i < 15; i++) {
            BlockState expected = base15[i];
            BlockState actual = section.getBlockState(i, 0, 0);
            if (expected != actual) {
                helper.fail("cell " + i + " corrupted after nibble->byte expansion: " + actual + " != " + expected);
            }
        }
        helper.assertTrue(section.getBlockState(15, 0, 0) == Blocks.DIAMOND_BLOCK.defaultBlockState(),
                "16th cell did not survive expansion");
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void uniformAirAndHasOnlyAir_fastPath(GameTestHelper helper) {
        // A factory-created section starts as a uniform-air container.
        LevelChunkSection section = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        helper.assertTrue(section.getStates() instanceof OptimizedPalettedContainer<?>,
                "states must be optimized");
        OptimizedPalettedContainer<?> states = (OptimizedPalettedContainer<?>) section.getStates();
        helper.assertTrue(states.isUniformAir(),
                "fresh factory section must be uniform air");
        helper.assertTrue(section.hasOnlyAir(),
                "fresh factory section must have only air");

        // Place one block -> no longer uniform air.
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        states = (OptimizedPalettedContainer<?>) section.getStates();
        helper.assertTrue(!states.isUniformAir(),
                "section with a block must not be uniform air");
        helper.assertTrue(!section.hasOnlyAir(),
                "section with a block must not have only air");
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void biomeContainer_functional(GameTestHelper helper) {
        // A real factory-created section's biome container must be optimized and
        // survive a write/read round-trip (biomes are holder-indexed).
        LevelChunkSection section = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        helper.assertTrue(section.getBiomes() instanceof OptimizedPalettedContainer<?>,
                "section biomes must be an OptimizedPalettedContainer");

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        section.write(buffer);

        LevelChunkSection restored = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        buffer.readerIndex(0);
        restored.read(buffer);

        helper.assertTrue(restored.getBiomes() instanceof OptimizedPalettedContainer<?>,
                "read path must keep biomes optimized");
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> expected = section.getBiomes().get(x, y, z);
                    Holder<Biome> actual = restored.getBiomes().get(x, y, z);
                    if (expected != actual) {
                        helper.fail("biome save/load mismatch at (" + x + "," + y + "," + z + "): "
                                + actual + " != " + expected);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20, padding = 48)
    public void unpackMixinPath_rebuildSection(GameTestHelper helper) {
        // Exercise the static PalettedContainer.unpack path (redirected by
        // PalettedContainerMixin) to rebuild a section from a packed container —
        // the NBT/chunk-load path mixin, which section.read(FriendlyByteBuf) bypasses.
        BlockState[] palette = FILL_PALETTE;
        LevelChunkSection original = new LevelChunkSection(
                PalettedContainerFactory.create(helper.getLevel().registryAccess()));
        fill(original, palette);

        Strategy<BlockState> strategy = Strategy.createForBlockStates(
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
        PalettedContainerRO.PackedData<BlockState> packed = original.getStates().pack(strategy);
        DataResult<PalettedContainer<BlockState>> result = PalettedContainer.unpack(strategy, packed);
        helper.assertTrue(result.error().isEmpty(),
                "unpack through mixin path errored: " + result.error());
        PalettedContainer<BlockState> rebuilt = result.result().orElseThrow();
        helper.assertTrue(rebuilt instanceof OptimizedPalettedContainer<?>,
                "unpack must produce an optimized container");

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState expected = original.getBlockState(x, y, z);
                    BlockState actual = rebuilt.get(x, y, z);
                    if (expected != actual) {
                        helper.fail("unpack rebuild mismatch at (" + x + "," + y + "," + z + "): "
                                + actual + " != " + expected);
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

    private static void assertSectionsEqual(GameTestHelper helper, LevelChunkSection expected,
            LevelChunkSection actual, String label) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState e = expected.getBlockState(x, y, z);
                    BlockState a = actual.getBlockState(x, y, z);
                    if (e != a) {
                        helper.fail(label + " mismatch at (" + x + "," + y + "," + z + "): " + a + " != " + e);
                    }
                }
            }
        }
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
