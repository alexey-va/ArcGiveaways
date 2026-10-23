import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    id("io.github.drownek.plugwright") version "2.0.4"
}

group = "ru.ruscrafting"
version = "0.1.32"
description = "Cross-server proximity giveaways for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

val e2eArcJar = providers.gradleProperty("e2eArcJar").map { file(it) }
val e2eArcCoreJar = providers.gradleProperty("e2eArcCoreJar").map { file(it) }
val verifyE2eProvider = tasks.register("verifyE2eProvider") {
    description = "Verifies the standalone provider used by the isolated Paper fixture."
    inputs.file(e2eArcCoreJar).optional()
    doLast {
        val provider = requireNotNull(e2eArcCoreJar.orNull) {
            "Supply -Pe2eArcCoreJar=/absolute/path/ArcCore-0.1.13.jar for the real Paper fixture"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(provider.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == "952cca75616a433f7ee02ea0a0d078b4b88291dafd62e7acb415be3952dbef10") {
            "ArcCore 0.1.13 fixture checksum mismatch: $digest"
        }
    }
}
tasks.withType<me.drownek.plugwright.AbstractPlugwrightTask>().configureEach {
    dependsOn(verifyE2eProvider)
}

plugwright {
    minecraftVersion.set("1.21.11")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    downloadPlugins {
        url("https://github.com/retrooper/packetevents/releases/download/v2.12.1/packetevents-spigot-2.12.1.jar")
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
    }
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        e2eArcJar.orNull?.let { file("plugins/ARC.jar", it) }
        e2eArcCoreJar.orNull?.let { file("plugins/ArcCore.jar", it) }
        file("plugins/ARC/modules/runtime.yml", projectDir.resolve("src/test/e2e/fixtures/arc-runtime.yml"))
        file("plugins/ARC/modules/ops-http.yml", projectDir.resolve("src/test/e2e/fixtures/arc-ops-http.yml"))
        file("plugins/ArcGiveaways/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml").readText())
        file("plugins/ArcGiveaways/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/redis.yml").readText())
    }
}

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.59")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.59")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.59")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.59")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.59")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.59")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.59")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.59")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.59")
    testImplementation("ru.ruscrafting.arc:arc-core-testing:2.7.59")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.59")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        providers.gradleProperty("ruscraftingOpsRoot")
            .orElse(providers.environmentVariable("RUSCRAFTING_OPS_ROOT"))
            .orNull
            ?.let { systemProperty("ruscrafting.opsRoot", it) }
    }
    register<Test>("integrationTest") {
        description = "Runs the disposable Redis integration test."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
        exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
        exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
        exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
        exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
        exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
        exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
        exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
        exclude("ru/arc/paper/api/**")
    }
    check { dependsOn(shadowJar, "integrationTest") }
}
