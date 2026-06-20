package com.github.uright008.nep.mixin;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import com.mojang.serialization.DataResult;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin {
    @Inject(method = "unpack", at = @At("HEAD"), cancellable = true)
    private static <T> void nep$unpackOptimizedContainer(
        final Strategy<T> strategy,
        final PalettedContainerRO.PackedData<T> packedData,
        final CallbackInfoReturnable<DataResult<PalettedContainer<T>>> cir
    ) {
        cir.setReturnValue(OptimizedPalettedContainer.unpack(strategy, packedData));
    }
}
