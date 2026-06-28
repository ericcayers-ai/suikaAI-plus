package dev.suika.app;

import java.nio.file.Path;

/**
 * Configuration for exporting a trained model to ONNX for in-app inference (ROADMAP §IX, §II.4).
 *
 * <p>Workflow: train in Python → export to ONNX → load via DJL/ONNX Runtime on the JVM →
 * run inference in the game with no Python dependency for the end-user.
 */
public record OnnxExportConfig(
        Path   outputPath,
        String modelName,
        int    inputDim,
        int    outputDim,
        int    opsetVersion
) {
    public static OnnxExportConfig defaults(Path directory) {
        return new OnnxExportConfig(
                directory.resolve("policy.onnx"),
                "SuikaPolicy",
                dev.suika.env.StateObservationEncoder.TOTAL,
                32,   // action bins
                17    // ONNX opset 17 (compatible with ONNX Runtime 1.16+)
        );
    }
}
