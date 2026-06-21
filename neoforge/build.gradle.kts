plugins {
	id("java-library")
	id("net.neoforged.moddev") version "2.0.141"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

// Include shared (mixin) code from core/
sourceSets {
	main {
		java {
			srcDir("../common/src/main/java")
		}
		resources {
			srcDir("../common/src/main/resources")
		}
	}
}

neoForge {
	version = providers.gradleProperty("neoforge_version").get()
}

repositories {
	mavenCentral()
}


tasks.processResources {
	val ver: String = project.version.toString()
	inputs.property("version", ver)
	filesMatching("META-INF/neoforge.mods.toml") {
		expand("version" to ver)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)
	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}
