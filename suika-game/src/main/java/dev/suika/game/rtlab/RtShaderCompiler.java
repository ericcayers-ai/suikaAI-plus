package dev.suika.game.rtlab;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles our embedded GLSL shader sources to SPIR-V at runtime via libshaderc
 *  (bundled by LWJGL) — deliberately avoids requiring the user to install the
 *  Vulkan SDK's glslc/glslangValidator just to run the RT Lab. */
final class RtShaderCompiler implements AutoCloseable {

    private final long compiler;
    private final long options;

    RtShaderCompiler() {
        this.compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc_compiler_initialize failed");
        this.options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
    }

    /** Returns SPIR-V bytecode (caller decides how long the module lives). */
    ByteBuffer compile(String source, int kind, String debugName) {
        long result = shaderc_compile_into_spv(compiler, source, kind, debugName, "main", options);
        try {
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                String log = shaderc_result_get_error_message(result);
                throw new IllegalStateException("Shader compile failed (" + debugName + "): " + log);
            }
            long length = shaderc_result_get_length(result);
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            spirv.limit((int) length);
            // Copy out of shaderc's own result buffer before we release it below.
            ByteBuffer copy = ByteBuffer.allocateDirect((int) length);
            copy.put(spirv);
            copy.flip();
            return copy;
        } finally {
            shaderc_result_release(result);
        }
    }

    @Override
    public void close() {
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
    }
}
