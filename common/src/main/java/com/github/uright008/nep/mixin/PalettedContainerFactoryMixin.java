package com.github.uright008.nep.mixin;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PalettedContainerFactory.class)
public abstract class PalettedContainerFactoryMixin {
    @Inject(method = "createForBlockStates", at = @At("HEAD"), cancellable = true)
    private void nep$createOptimizedBlockStateContainer(final CallbackInfoReturnable<PalettedContainer<BlockState>> cir) {
        PalettedContainerFactory factory = (PalettedContainerFactory)(Object)this;
        cir.setReturnValue(new OptimizedPalettedContainer<>(factory.defaultBlockState(), factory.blockStatesStrategy()));
    }

    @Inject(method = "createForBiomes", at = @At("HEAD"), cancellable = true)
    private void nep$createOptimizedBiomeContainer(final CallbackInfoReturnable<PalettedContainer<Holder<Biome>>> cir) {
        PalettedContainerFactory factory = (PalettedContainerFactory)(Object)this;
        cir.setReturnValue(new OptimizedPalettedContainer<>(factory.defaultBiome(), factory.biomeStrategy()));
    }
}
