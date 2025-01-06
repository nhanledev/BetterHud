plugins {
    id("standard-conventions")
    id("com.modrinth.minotaur")
}

val shade = configurations.create("shade")
val dist = rootProject.project("dist")

dependencies {
    compileOnly(shade("me.lucko:jar-relocator:1.7") {
        exclude("org.ow2.asm")
    })
    compileOnly(shade(dist)!!)
    testImplementation(project(":api:standard-api"))
    testImplementation(dist)
    shade(libs.kotlinStdlib)
}

val excludeDependencies = listOf(
    "annotations-13.0.jar"
)

tasks {
    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(shade.dependencies)
        from(shade
            .asSequence()
            .distinctBy {
                it.name
            }.filter {
                !excludeDependencies.contains(it.name)
            }.map { file ->
                zipTree(file)
            }.toList()
        ) {
            exclude("META-INF/MANIFEST.MF")
        }
    }
}

modrinth {
    val log = System.getenv("COMMIT_MESSAGE")
    if (log != null) {
        versionType = "alpha"
        changelog = log
    } else {
        versionType = "release"
        changelog = rootProject.file("changelog/${project.version}.md").readText()
    }
    token = System.getenv("MODRINTH_API_TOKEN")
    projectId = "betterhud2"
    versionNumber = project.version as String
}