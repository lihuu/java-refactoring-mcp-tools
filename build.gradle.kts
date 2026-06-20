import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // IntelliJ Platform Gradle Plugin 2.x: platform + bundled plugins + test framework
    // are declared here as dependencies, not in a top-level `intellij {}` block.
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("com.intellij.java")

        // Required for BasePlatformTestCase / LightJavaCodeInsightFixtureTestCase.
        testFramework(TestFrameworkType.Platform)
    }

    // The platform test framework runs on JUnit 4.
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // Single source of truth for the JDK: gradle.properties `javaVersion`.
    // If the IDE's bundled runtime / Kotlin compiler rejects this value, lower
    // it there (e.g. 25 -> 21); no other build change is required.
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
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
