import org.gradle.jvm.tasks.Jar

plugins {
	base
}

val modVersion = providers.gradleProperty("mod_version").get()

tasks.register<Jar>("hybridJar") {
	val fabricJar = project(":fabric").tasks.named<Jar>("jar").flatMap { it.archiveFile }
	val neoforgeJar = project(":neoforge").tasks.named<Jar>("jar").flatMap { it.archiveFile }

	dependsOn(fabricJar, neoforgeJar)

	archiveBaseName = rootProject.name
	archiveVersion = modVersion
	destinationDirectory = layout.buildDirectory.dir("libs")
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from(zipTree(fabricJar))
	from(zipTree(neoforgeJar))
}

tasks.named("assemble") {
	dependsOn("hybridJar")
}
