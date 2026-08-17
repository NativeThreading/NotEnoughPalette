package com.github.uright008.nep.mixin;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import net.caffeinemc.mods.sodium.client.world.PalettedContainerROExtension;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes {@link OptimizedPalettedContainer} implement Sodium's
 * {@link PalettedContainerROExtension} so Sodium's chunk renderer
 * ({@code LevelSlice.unpackBlockData}) can bulk-unpack a whole section through
 * NEP's direct-array {@code fillArray} instead of the inherited
 * {@code sodium$unpack} which reads the stale {@code PalettedContainer.data}
 * field.
 *
 * <p>{@code unique = false} forces the interface (and its bridge methods) to
 * be added even though {@code PalettedContainer} already inherits
 * {@code PalettedContainerROExtension} from Sodium's own mixin — otherwise
 * the inherited implementation (which accesses the stale {@code data} field)
 * would be used and all blocks would render as air.</p>
 *
 * <p>Priority 1001 ensures NEP is applied <em>after</em> Sodium's default-1000
 * mixin, so the bridge methods correctly override the inherited ones.</p>
 *
 * <p>The mixin config is {@code required: false}, so it is skipped entirely
 * when Sodium is absent.</p>
 */
@Mixin(value = OptimizedPalettedContainer.class, priority = 1001)
@Implements({
    @Interface(iface = PalettedContainerROExtension.class, prefix = "nep$", unique = false)
})
public abstract class SodiumPalettedContainerExtensionMixin<T> {

    @Shadow
    protected abstract T get(int index);

    /** Bulk-unpack the whole section — NEP's direct-array fast path. */
    public void nep$sodium$unpack(final T[] values) {
        ((OptimizedPalettedContainer<T>)(Object)this).fillArray(values);
    }

    /** Region-limited unpack (used for neighbouring sections). */
    public void nep$sodium$unpack(final T[] values, final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int local = (y << 4 | z) << 4 | x;
                    values[local] = this.get(local);
                }
            }
        }
    }

    /** Sodium clones the container before meshing; delegate to NEP's copy(). */
    public PalettedContainerRO<T> nep$sodium$copy() {
        return ((OptimizedPalettedContainer<T>)(Object)this).copy();
    }
}