rootProject.name = "ArcGiveaways"

val arcCoreDir = sequenceOf(
    file("../arc-core"),
    file("../../IdeaProjects/arc-core"),
).firstOrNull { it.resolve("settings.gradle.kts").isFile }
    ?: error("arc-core must be checked out next to ArcGiveaways")

includeBuild(arcCoreDir)
