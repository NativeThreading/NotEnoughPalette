plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
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

loom {
	splitEnvironmentSourceSets()

	mods {
		register("nep") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

fabricApi {
	configureTests {
		createSourceSet = true
		modId = "nep-gametest"
		enableGameTests = true
		eula = true
	}
}

repositories {
	mavenCentral()
}

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

	testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.assertj:assertj-core:3.27.0")
}

tasks.test {
	useJUnitPlatform()
}

tasks.processResources {
	val ver: String = project.version.toString()
	inputs.property("version", ver)
	filesMatching("fabric.mod.json") {
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
