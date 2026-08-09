package com.github.uright008.nep;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.Strategy;
import com.github.uright008.nep.palette.OptimizedPalettedContainer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JUnit5 + AssertJ + loom-MC + NEP wiring resolves: constructing a
 * {@link OptimizedPalettedContainer} with a fake {@code IdMap<String>} and the
 * vanilla block-state {@link Strategy} works without a Minecraft bootstrap.
 */
class TestInfrastructureSmokeTest {

    @Test
    void smoke_constructsNepContainer_readsDefault() {
        IdMap<String> idMap = TestIdMap.blockStates("air", "stone");
        Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        OptimizedPalettedContainer<String> container =
                new OptimizedPalettedContainer<>("air", strategy);

        assertThat(container.get(0, 0, 0)).isEqualTo("air");
        assertThat(container.bitsPerEntry()).isZero();
    }
}
