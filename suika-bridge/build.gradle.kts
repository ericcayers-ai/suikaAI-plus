val onnxruntimeVersion: String by project

// suika-bridge: Java↔Python TCP Gym bridge, Gym/PettingZoo adapters, ONNX Runtime deploy
dependencies {
    implementation(project(":suika-core"))
    implementation(project(":suika-env"))
    // Measured choice over DJL: smaller surface for policy-only inference, bundled
    // CPU natives, and a clear CUDA→CPU SessionOptions fallback path.
    implementation("com.microsoft.onnxruntime:onnxruntime:${onnxruntimeVersion}")
}
