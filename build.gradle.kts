import org.gradle.jvm.tasks.Jar

plugins {
	base
}

fun gitVersion(): String {
	val tag = try { ProcessBuilder("git", "describe", "--tags", "--exact-match").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
	if (tag.isNotEmpty() && tag.startsWith("v")) return tag.substring(1)
	val sha = try { ProcessBuilder("git", "rev-parse", "--short=8", "HEAD").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
	if (sha.isNotEmpty()) return sha
	return providers.gradleProperty("mod_version").get()
}

tasks.register<Jar>("hybridJar") {
	val fabricJar = project(":fabric").tasks.named<Jar>("jar").flatMap { it.archiveFile }
	val neoforgeJar = project(":neoforge").tasks.named<Jar>("jar").flatMap { it.archiveFile }

	dependsOn(fabricJar, neoforgeJar)

	archiveBaseName = "notenoughpalette"
	archiveVersion = gitVersion()
	destinationDirectory = layout.buildDirectory.dir("libs")
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from(zipTree(fabricJar))
	from(zipTree(neoforgeJar))
}

tasks.named("assemble") {
	dependsOn("hybridJar")
}
