package com.github.uright008.nep.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers both branches of the Sodium presence check that gates
 * {@code notenoughpalette.sodium.mixins.json}. Sodium is {@code compileOnly}, so the test
 * runtime classpath is the "Sodium absent" case; a fake classpath entry stands in for the
 * installed-mod case.
 */
class SodiumMixinPluginTest {
    private static final String RESOURCE_PATH =
        "net/caffeinemc/mods/sodium/client/world/PalettedContainerROExtension.class";

    @Test
    void reportsAbsentWhenSodiumIsNotOnClasspath() {
        assertThat(SodiumMixinPlugin.isSodiumPresent(getClass().getClassLoader())).isFalse();
    }

    @Test
    void reportsPresentWhenSodiumInterfaceIsOnClasspath(@TempDir final Path tempDir) throws Exception {
        Path fakeInterface = tempDir.resolve(RESOURCE_PATH);
        Files.createDirectories(fakeInterface.getParent());
        Files.writeString(fakeInterface, "");

        try (URLClassLoader withSodium = new URLClassLoader(
                new URL[] {tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(SodiumMixinPlugin.isSodiumPresent(withSodium)).isTrue();
        }
    }
}
