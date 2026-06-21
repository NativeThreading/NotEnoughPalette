pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		maven {
			name = "NeoForge"
			url = uri("https://maven.neoforged.net/releases")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version("0.9.0")
}

rootProject.name = "nep"

include(":fabric")
include(":neoforge")
