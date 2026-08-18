import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.Sync
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // IntelliJ Platform Gradle Plugin 2.x: platform + bundled plugins + test framework
    // are declared here as dependencies, not in a top-level `intellij {}` block.
    // IntelliJ IDEA 2025.3+ uses the unified IntelliJ IDEA distribution helper.
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.mcpServer")

        // Required for BasePlatformTestCase / LightJavaCodeInsightFixtureTestCase.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    // The platform test framework runs on JUnit 4.
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // Single source of truth for the JDK used to compile: gradle.properties `javaVersion`.
    // `jvmToolchain(25)` compiles with JDK 25; the `jvmTarget` below pins the bytecode at Java
    // 21 because IntelliJ IDEA 2026.1 runs on JBR 21 and Kotlin 2.4's jvmTarget tops out at
    // JVM 24 — JDK 25 bytecode would neither compile (jvmTarget ceiling) nor load in the IDE.
    // Lower `javaVersion` there (e.g. 25 -> 21) to also drop the toolchain.
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Pin the bytecode target for every Kotlin compilation (main, test, and any additional source
// set). The IntelliJ Platform Gradle Plugin can otherwise default it to the toolchain's JDK.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Keep Java bytecode at Java 21 too, matching the Kotlin jvmTarget above. The `java` plugin
// defaults `compileJava` to the JDK 25 toolchain, which triggers the Kotlin/Java JVM-target
// check; pinning the Java release to 21 keeps the two tasks consistent.
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.release.set(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}

val e2eFixtureSource = layout.projectDirectory.dir("src/test/testData/e2e/java-refactor-fixture")
val e2eWorkspace = layout.buildDirectory.dir("e2e-workspace")
val e2eMcpPort = 3001

val prepareE2eFixture = tasks.register<Sync>("prepareE2eFixture") {
    group = "verification"
    description = "Resets the disposable Java project used by real IDEA MCP acceptance."
    from(e2eFixtureSource)
    into(e2eWorkspace)
    includeEmptyDirs = false
}

val runE2eIde = intellijPlatformTesting.runIde.register("runE2eIde") {
    sandboxDirectory.set(layout.buildDirectory.dir("e2e-sandbox"))

    prepareSandboxTask {
        doLast {
            val optionsDirectory = sandboxConfigDirectory.get().asFile.resolve("options")
            optionsDirectory.mkdirs()
            optionsDirectory.resolve("mcpServer.xml").writeText(
                """
                <application>
                  <component name="McpServerSettings">
                    <option name="enableMcpServer" value="true" />
                    <option name="mcpServerPort" value="$e2eMcpPort" />
                  </component>
                </application>
                """.trimIndent(),
            )
            optionsDirectory.resolve("trusted-paths.xml").writeText(
                """
                <application>
                  <component name="Trusted.Paths">
                    <option name="TRUSTED_PROJECT_PATHS">
                      <map>
                        <entry key="${e2eWorkspace.get().asFile.absolutePath}" value="true" />
                      </map>
                    </option>
                  </component>
                </application>
                """.trimIndent(),
            )
        }
    }

    task {
        group = "verification"
        description = "Launches the disposable Java project in an MCP-enabled IDEA E2E sandbox."
        dependsOn(prepareE2eFixture)
        args(e2eWorkspace.get().asFile.absolutePath)
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Didea.trust.all.projects=true",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
            )
        }
    }
}
