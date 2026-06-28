val libgdxVersion: String by project

// suika-dash: live dashboard (ImGui+ImPlot), replay viewer, exporters
// imgui-java natives are added when Phase 6 wires the live dashboard.
dependencies {
    implementation(project(":suika-core"))
    implementation(project(":suika-env"))
    implementation("com.badlogicgames.gdx:gdx:${libgdxVersion}")
    // Dashboard UI — add platform natives in Phase 6:
    //   implementation("io.github.spair:imgui-java-app:1.86.11")
}
