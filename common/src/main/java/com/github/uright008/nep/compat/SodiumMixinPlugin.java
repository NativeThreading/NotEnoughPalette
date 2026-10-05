package com.github.uright008.nep.compat;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Sodium compatibility mixin only when Sodium is actually installed.
 *
 * <p>{@code SodiumPalettedContainerExtensionMixin} adds Sodium's
 * {@code PalettedContainerROExtension} interface to
 * {@link com.github.uright008.nep.palette.OptimizedPalettedContainer}. Mixin resolves that
 * interface while preparing the mixin class, so without this gate every startup without Sodium
 * logs a {@code ClassNotFoundException} followed by a {@code MixinPreProcessorException}
 * prepare error. The config is already {@code required: false}, but that only makes the failure
 * non-fatal — it still gets logged at ERROR level. Returning {@code false} here keeps Mixin from
 * touching the mixin at all.</p>
 *
 * <p>Detection is class presence based rather than loader API based, so one shared plugin works
 * on both Fabric and NeoForge: both expose installed mod jars on the classloader that loads this
 * class. A resource lookup is tried first because it avoids loading Sodium's class during mixin
 * config preparation; {@code Class.forName} is the fallback for classloaders that do not publish
 * mod resources.</p>
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_INTERFACE =
        "net.caffeinemc.mods.sodium.client.world.PalettedContainerROExtension";
    private static final String SODIUM_INTERFACE_RESOURCE =
        SODIUM_INTERFACE.replace('.', '/') + ".class";

    private final boolean sodiumPresent = isSodiumPresent(SodiumMixinPlugin.class.getClassLoader());

    /**
     * @param loader classloader that can see the mods installed in this environment
     * @return whether Sodium's {@code PalettedContainerROExtension} is reachable
     */
    static boolean isSodiumPresent(final ClassLoader loader) {
        if (loader.getResource(SODIUM_INTERFACE_RESOURCE) != null) {
            return true;
        }
        try {
            Class.forName(SODIUM_INTERFACE, false, loader);
            return true;
        } catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return this.sodiumPresent;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass,
            final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass,
            final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
