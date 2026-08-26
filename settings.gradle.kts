rootProject.name = "ArcGiveaways"

providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
        "arcCoreDir must point to an arc-core checkout"
    }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            listOf(
                "arc-core",
                "arc-core-integration-testing",
                "arc-core-paper",
                "arc-core-paper-testing",
                "arc-core-redis",
            ).forEach { artifact ->
                substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact"))
            }
        }
    }
}
