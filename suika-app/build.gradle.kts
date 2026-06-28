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
    implementation(project(":suika-dash"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-platform:${libgdxVersion}:natives-desktop")
}

application {
    mainClass = "dev.suika.app.SuikaApplication"
}
