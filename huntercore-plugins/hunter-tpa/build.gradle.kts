dependencies {
    compileOnly(project(":divinemc-api"))
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}
