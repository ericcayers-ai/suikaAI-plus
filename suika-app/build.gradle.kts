plugins {
    application
}

val libgdxVersion: String by project

// suika-app: assembled application — wires game + lab + dashboard + settings
dependencies {
    implementation(project(":suika-core"))
    implementation(project(":suika-assets"))
    implementation(project(":suika-game"))
    implementation(project(":suika-env"))
    implementation(project(":suika-ai"))
    implementation(project(":suika-bridge"))
    implementation(project(":suika-dash"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-platform:${libgdxVersion}:natives-desktop")
}

application {
    mainClass = "dev.suika.app.SuikaApplication"
}

// ---- jpackage: produce a native Windows .exe installer ----
// Usage: ./gradlew :suika-app:jpackageExe
// Requires: JDK 21+ (jpackage is bundled with the JDK), WiX Toolset on PATH for MSI
tasks.register<Exec>("jpackageExe") {
    dependsOn("installDist")

    val jpackage = "${System.getProperty("java.home")}/bin/jpackage"
    val installDir = layout.buildDirectory.dir("install/suika-app").get().asFile
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile

    doFirst { outDir.mkdirs() }

    commandLine(
        jpackage,
        "--type", "app-image",
        "--name", "SuikaAI",
        "--app-version", version.toString(),
        "--vendor", "ericcayers-ai",
        "--description", "Suika AI+ — AI Playground for the Watermelon Game",
        "--input", "$installDir/lib",
        "--main-jar", "suika-app-${version}.jar",
        "--main-class", "dev.suika.app.SuikaApplication",
        "--dest", outDir.absolutePath,
        "--win-console",                // keep a console window for debug; remove for production
        "--java-options", "--enable-preview",
        "--java-options", "-Xmx512m"
    )

    doLast {
        println("jpackage output: $outDir")
        outDir.listFiles()?.forEach { println("  -> ${it.name}") }
    }
}
