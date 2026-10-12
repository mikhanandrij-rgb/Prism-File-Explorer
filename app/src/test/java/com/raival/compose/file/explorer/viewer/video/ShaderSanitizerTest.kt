package com.raival.compose.file.explorer.viewer.video

import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShaderSanitizerTest {

    private val assetDirs = listOf("anime4k", "artcnn").map { File("src/main/assets", it) }

    @Test
    fun removesCommentsAfterDefines() {
        val out = ShaderSanitizer.sanitize(
            """
            //!HOOK MAIN
            //!BIND HOOKED
            #define KERNELSIZE 5 //Kernel size, must be an positive odd integer.
            #define HALF 2 /* half */
            vec4 hook() { return HOOKED_tex(HOOKED_pos); } // trailing
            """.trimIndent()
        )
        assertTrue(out.contains("#define KERNELSIZE 5\n"))
        assertTrue(out.contains("#define HALF 2\n"))
        assertFalse(out.lines().filterNot { it.trimStart().startsWith("//!") }.any { it.contains("//") })
    }

    @Test
    fun injectsFunctionLikeWrappersAfterEachHeader() {
        val out = ShaderSanitizer.sanitize(
            """
            //!DESC pass one
            //!HOOK MAIN
            //!BIND HOOKED
            vec4 hook() { return MAIN_texOff(vec2(1, 0)); }

            //!DESC pass two
            //!HOOK PREKERNEL
            //!BIND HOOKED
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            """.trimIndent()
        )
        assertTrue(out.contains("#define MAIN_texOff(off) MAINPRESUB_texOff(off)"))
        assertTrue(out.contains("#define HOOKED_tex(pos) MAINPRESUB_tex(pos)"))
        assertTrue(out.contains("#define HOOKED_tex(pos) PREKERNEL_tex(pos)"))
        // Wrappers come right after the headers, before the code of each pass.
        val lines = out.lines()
        val firstHook = lines.indexOfFirst { it.startsWith("vec4 hook") }
        val firstWrapper = lines.indexOfFirst { it.startsWith("#define MAIN_tex(") }
        val lastHeader = lines.indexOf("//!BIND HOOKED")
        assertTrue(firstWrapper in (lastHeader + 1) until firstHook)
        assertEquals(2, out.split("#define MAIN_texOff(off)").size - 1)
    }

    @Test
    fun keepsEveryDirectiveOfTheBundledShaders() {
        assetDirs.flatMap { it.listFiles { f -> f.extension == "glsl" }!!.toList() }.forEach { file ->
            val source = file.readText()
            val out = ShaderSanitizer.sanitize(source)
            val before = source.lines().filter { it.trimStart().startsWith("//!") }
            val after = out.lines().filter { it.trimStart().startsWith("//!") }
            assertEquals("directives changed in ${file.name}", before, after)
            val passes = before.count { it.startsWith("//!DESC") }
            assertEquals("wrappers per pass in ${file.name}", passes, out.split("#define MAIN_tex(pos)").size - 1)
            assertFalse(
                "comment left in a #define of ${file.name}",
                out.lines().any { it.startsWith("#define") && it.contains("//") }
            )
        }
    }

    @Test
    fun rewritesDesktopGlslForGlslEs() {
        val out = ShaderSanitizer.sanitize(
            """
            //!DESC conv
            //!COMPUTE 24 32 12 16
            //!HOOK LUMA
            //!BIND LUMA
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable
            const ivec2 ksize = ivec2(3, 3);
            void hook() {
                const uvec2 local_xy = gl_LocalInvocationID.xy;
                for (uint y = local_xy.y; y < 4; y += 1) {
                    for (uint x = local_xy.x; x < 4; x += 1) {
                    }
                }
                for (uint k = other.x; k < 4u; k++) {}
                const vec4 v = LUMA_tex(LUMA_pos);
            }
            """.trimIndent()
        )
        assertFalse(out.contains("#extension"))
        assertTrue(out.contains("const ivec2 ksize = ivec2(3, 3);"))  // globals stay const
        assertTrue(out.contains("    ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);"))
        assertTrue(out.contains("for (int y = local_xy.y;"))
        assertTrue(out.contains("for (int x = local_xy.x;"))
        assertTrue(out.contains("for (uint k = other.x;"))  // unrelated uint loops untouched
        assertTrue(out.contains("    vec4 v = LUMA_tex(LUMA_pos);"))
    }

    @Test
    fun bundledComputeShadersBecomeGlslEs() {
        assetDirs.drop(1).flatMap { it.listFiles { f -> f.extension == "glsl" }!!.toList() }.forEach { file ->
            val code = ShaderSanitizer.sanitize(file.readText()).lines().filterNot { it.startsWith("//!") }
            assertFalse("#extension left in ${file.name}", code.any { it.trimStart().startsWith("#extension") })
            assertFalse("local const left in ${file.name}", code.any { Regex("""^\s+const\s""").containsMatchIn(it) })
            assertFalse("uint thread id left in ${file.name}", code.any { it.contains("uvec2 local_xy") })
        }
    }
}
