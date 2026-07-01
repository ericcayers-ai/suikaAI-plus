val libgdxVersion: String by project
val lwjglVersion: String by project

dependencies {
    implementation(project(":suika-core"))
    implementation(project(":suika-assets"))
    implementation(project(":suika-env"))
    implementation(project(":suika-ai"))
    implementation("com.badlogicgames.gdx:gdx:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-platform:${libgdxVersion}:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype:${libgdxVersion}")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:${libgdxVersion}:natives-desktop")

    // RT Lab: raw Vulkan (KHR ray tracing extensions) for the experimental hardware
    // ray-traced preview. Separate from LibGDX's own OpenGL rendering entirely — its
    // own window, its own instance/device. lwjgl-shaderc compiles our GLSL to SPIR-V
    // at runtime so this doesn't require the user to install the Vulkan SDK.
    implementation("org.lwjgl:lwjgl-vulkan:${lwjglVersion}")
    implementation("org.lwjgl:lwjgl-shaderc:${lwjglVersion}")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:${lwjglVersion}:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:${lwjglVersion}:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:${lwjglVersion}:natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:${lwjglVersion}:natives-macos-arm64")
}
