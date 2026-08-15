package moe.rukamori.archivetune.desktop.lyrics.anim

import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.skia.RuntimeEffect

class KawarpShaderTest {
    @Test fun `kawarp shader compiles`() {
        val effect = RuntimeEffect.makeForShader(kawarpShaderSource())
        assertNotNull(effect, "SkSL failed to compile")
    }
}
