import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.tasks.Jar

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-SNAPSHOT"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurRef")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("divinemc-server/build.gradle.kts")
            patchFile = file("divinemc-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("divinemc-api/build.gradle.kts")
            patchFile = file("divinemc-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("divinemc-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("divinemc-api/purpur-patches")
            outputDir = file("purpur-api")
        }
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.compileJava {
        options.compilerArgs.add("-Xlint:-deprecation")
        options.isWarnings = false
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4G"
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.bxteam.org/snapshots") {
                name = "divinemc"

                credentials.username = System.getenv("REPO_USERNAME")
                credentials.password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}

val bundledPluginOutput = layout.buildDirectory.dir("huntercore/bundled-plugins")
val prepareExternalBundledPlugins by tasks.registering(Exec::class) {
    group = "huntercore"
    description = "Downloads and builds external HunterCore bundled plugin jars."
    val outputDir = bundledPluginOutput.get().asFile
    inputs.file(layout.projectDirectory.file("scripts/prepare-bundled-plugins.sh"))
    outputs.dir(outputDir)
    commandLine("bash", layout.projectDirectory.file("scripts/prepare-bundled-plugins.sh").asFile.absolutePath, outputDir.absolutePath)
}

gradle.projectsEvaluated {
    val tpaJar = project(":huntercore-plugins:hunter-tpa").tasks.named<Jar>("jar")
    val authJar = project(":huntercore-plugins:hunter-auth").tasks.named<Jar>("jar")

    project(":divinemc-server").tasks.named<ProcessResources>("processResources") {
        dependsOn(prepareExternalBundledPlugins, tpaJar, authJar)
        from(bundledPluginOutput.map { it.dir("plugins") }) {
            into("META-INF/huntercore/bundled-plugins")
        }
        from(bundledPluginOutput.map { it.file("bundled-plugins.external.yml") }) {
            into("META-INF/huntercore")
        }
        from(tpaJar.flatMap { it.archiveFile }) {
            into("META-INF/huntercore/bundled-plugins")
            rename { "HunterTPA.jar" }
        }
        from(authJar.flatMap { it.archiveFile }) {
            into("META-INF/huntercore/bundled-plugins")
            rename { "HunterAuth.jar" }
        }
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}
