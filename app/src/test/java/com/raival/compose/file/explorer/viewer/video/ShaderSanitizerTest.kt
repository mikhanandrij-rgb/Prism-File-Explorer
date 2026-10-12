package com.raival.compose.file.explorer.viewer.video

import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShaderSanitizerTest {

    private val assetsDir = File("src/main/assets/anime4k")

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
        assetsDir.listFiles { f -> f.extension == "glsl" }!!.forEach { file ->
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
}
