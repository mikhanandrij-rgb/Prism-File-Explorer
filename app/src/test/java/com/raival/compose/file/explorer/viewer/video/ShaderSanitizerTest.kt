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
            // Wide passes are split first (see widePassesAreSplitExactly); everything else
            // keeps its directives.
            val source = ShaderSanitizer.splitWidePasses(file.readText())
            val out = ShaderSanitizer.sanitize(file.readText())
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

    private val termLine = Regex("""^\s*(?:vec4\s+result\s*=|result\s*\+=)\s*(.+);\s*$""")

    /** Summed terms of a shader (initial values the splitter adds are not terms). */
    private fun terms(source: String): Map<String, Int> = source.lines()
        .mapNotNull { termLine.find(it)?.groupValues?.get(1)?.trim() }
        .filterNot { it == "vec4(0.0)" || Regex("""facet_split_\d+_tex\(facet_split_\d+_pos\)""").matches(it) }
        .groupingBy { it }.eachCount()

    private fun passes(source: String): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var previousHeader = false
        for (line in source.lines()) {
            val header = line.startsWith("//!")
            if (header && !previousHeader) result += mutableListOf<String>()
            result.lastOrNull()?.add(line)
            previousHeader = header
        }
        return result
    }

    @Test
    fun widePassesAreSplitExactly() {
        val files = assetDirs.flatMap { it.listFiles { f -> f.extension == "glsl" }!!.toList() }
        var split = 0
        files.forEach { file ->
            val source = file.readText()
            val out = ShaderSanitizer.sanitize(source)
            // No pass may bind more textures than mobile GPUs reliably allow.
            passes(out).forEach { pass ->
                val binds = pass.count { it.startsWith("//!BIND") }
                assertTrue("${file.name}: pass binds $binds textures", binds <= ShaderSanitizer.MAX_BINDS)
            }
            // The sum is unchanged: every term appears exactly as often as before.
            assertEquals("terms changed in ${file.name}", terms(source), terms(out))
            split += out.split("(part 1/2)").size - 1
        }
        assertTrue("expected wide passes in the bundled networks", split > 0)
    }

    @Test
    fun everyTextureASplitPassReadsIsBound() {
        val ref = Regex("""\b([A-Za-z_]\w*?)_(?:tex|texOff|gather|pos|pt|size|raw|mul)\b""")
        assetDirs.flatMap { it.listFiles { f -> f.extension == "glsl" }!!.toList() }.forEach { file ->
            val out = ShaderSanitizer.sanitize(file.readText())
            passes(out).filter { p -> p.any { it.contains("(part ") } }.forEach { pass ->
                val bound = pass.filter { it.startsWith("//!BIND") }.map { it.removePrefix("//!BIND").trim() }.toSet()
                val read = pass.filterNot { it.startsWith("//!") || it.startsWith("#") }
                    .flatMap { line -> ref.findAll(line).map { it.groupValues[1] }.toList() }
                    .filter { it.startsWith("conv") || it.startsWith("facet_split") || it == "MAIN" || it == "LUMA" }
                    .toSet()
                // g_N macros are expanded from their #define lines, which are kept in the pass.
                val defined = pass.filter { it.startsWith("#define g_") }
                    .flatMap { line -> ref.findAll(line).map { it.groupValues[1] }.toList() }.toSet()
                val missing = (read + defined) - bound
                assertTrue("${file.name} ${pass.first()}: unbound $missing", missing.isEmpty())
            }
        }
    }

    @Test
    fun narrowPassesAreUntouched() {
        val pass = """
            //!DESC narrow
            //!HOOK MAIN
            //!BIND MAIN
            //!BIND a
            vec4 hook() {
                vec4 result = vec4(0.1);
                result += mat4(1.0) * a_tex(a_pos);
                return result;
            }
        """.trimIndent()
        assertEquals(pass, ShaderSanitizer.splitWidePasses(pass))
    }
}
