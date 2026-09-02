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

    // This plugin's architecture intentionally drives native refactoring processors headlessly,
    // which the verifier classifies as internal / override-only API usage (a roadmap-declared
    // risk, tracked in the verifier reports). Keep only the categories that genuinely block a
    // marketplace release as fatal; API-usage findings stay visible in the reports.
    pluginVerification {
        failureLevel.set(listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        ))
    }

    // JetBrains Marketplace upload. Secrets are supplied per-invocation (CI or local shell),
    // never committed:
    //   export MARKETPLACE_TOKEN=...                       # marketplace.jetbrains.com publish token
    //   export MARKETPLACE_CERTIFICATE_CHAIN="$(cat chain.crt)"
    //   export MARKETPLACE_PRIVATE_KEY="$(cat key.pem)"
    //   export MARKETPLACE_PRIVATE_KEY_PASSWORD=...
    //   ./gradlew signPlugin publishPlugin
    publishing {
        token = providers.environmentVariable("MARKETPLACE_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("MARKETPLACE_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("MARKETPLACE_PRIVATE_KEY")
        password = providers.environmentVariable("MARKETPLACE_PRIVATE_KEY_PASSWORD")
    }
}

tasks {
    test {
        useJUnit()
    }
}

val e2eFixtureSource = layout.projectDirectory.dir("src/test/testData/e2e/java-refactor-fixture")
val e2eWorkspace = layout.buildDirectory.dir("e2e-workspace")
val e2eSandbox = layout.buildDirectory.dir("e2e-sandbox")
val e2eMcpPort = 3001

val demoProjectSource = layout.projectDirectory.dir("src/test/testData/e2e/demo-project")
val demoWorkspace = layout.buildDirectory.dir("demo-workspace")
val demoSandbox = layout.buildDirectory.dir("demo-sandbox")

val prepareE2eFixture = tasks.register<Sync>("prepareE2eFixture") {
    group = "verification"
    description = "Resets the disposable Java project used by real IDEA MCP acceptance."
    outputs.upToDateWhen { false }
    doFirst {
        delete(e2eWorkspace)
    }
    from(e2eFixtureSource)
    into(e2eWorkspace)
    includeEmptyDirs = false
}

val runE2eIde = intellijPlatformTesting.runIde.register("runE2eIde") {
    sandboxDirectory.set(e2eSandbox)

    prepareSandboxTask {
        doFirst {
            // The fixture path is stable across runs. Remove persisted project-model/editor caches
            // before IDEA opens it again, otherwise it can retain an old in-memory document while
            // `prepareE2eFixture` has already replaced the file on disk.
            delete(
                e2eSandbox.get().dir("config_runE2eIde").asFile,
                e2eSandbox.get().dir("system_runE2eIde").asFile,
                e2eSandbox.get().dir("log_runE2eIde").asFile,
            )
        }
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
            optionsDirectory.resolve("registry.xml").writeText(
                """
                <application>
                  <component name="Registry">
                    <entry key="ide.experimental.ui.onboarding" value="false" />
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
                "-Dide.experimental.ui.onboarding=false",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
            )
        }
    }
}

val prepareDemoProject = tasks.register<Sync>("prepareDemoProject") {
    group = "verification"
    description = "Resets the disposable demo project used for agent-driven refactoring experiments."
    outputs.upToDateWhen { false }
    doFirst {
        delete(demoWorkspace)
    }
    from(demoProjectSource)
    into(demoWorkspace)
    includeEmptyDirs = false
}

val runDemoIde = intellijPlatformTesting.runIde.register("runDemoIde") {
    sandboxDirectory.set(demoSandbox)

    prepareSandboxTask {
        doFirst {
            delete(
                demoSandbox.get().dir("config_runDemoIde").asFile,
                demoSandbox.get().dir("system_runDemoIde").asFile,
                demoSandbox.get().dir("log_runDemoIde").asFile,
            )
        }
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
                        <entry key="${demoWorkspace.get().asFile.absolutePath}" value="true" />
                      </map>
                    </option>
                  </component>
                </application>
                """.trimIndent(),
            )
            optionsDirectory.resolve("registry.xml").writeText(
                """
                <application>
                  <component name="Registry">
                    <entry key="ide.experimental.ui.onboarding" value="false" />
                  </component>
                </application>
                """.trimIndent(),
            )
        }
    }

    task {
        group = "verification"
        description = "Launches the demo project in an MCP-enabled IDEA sandbox for agent-driven refactoring."
        dependsOn(prepareDemoProject)
        args(demoWorkspace.get().asFile.absolutePath)
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Didea.trust.all.projects=true",
                "-Dide.experimental.ui.onboarding=false",
                "-Djb.consents.confirmation.enabled=false",
            )
        }
    }
}
