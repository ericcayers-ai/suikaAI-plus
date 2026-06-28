val libgdxVersion: String by project

dependencies {
    implementation(project(":suika-core"))
    implementation(project(":suika-assets"))
    implementation("com.badlogicgames.gdx:gdx:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-platform:${libgdxVersion}:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:${libgdxVersion}:natives-desktop")
}
