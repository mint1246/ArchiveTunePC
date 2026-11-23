package moe.rukamori.archivetune.shared

import kotlin.test.*

class SocialLoginCallbacksTest {
    @Test fun currentCallbackDecodesExactlyOneCode() {
        assertEquals("opaque+code", discordCallbackCode("http://127.0.0.1:49152/archivetune-auth?nonce=owned&code=opaque%2Bcode", "owned"))
    }
    @Test fun wrongSessionOriginsAndAmbiguousOrDeniedCallbacksAreRejected() {
        val invalid = listOf(
            "http://127.0.0.1:49152/archivetune-auth?nonce=other&code=abc",
            "https://example.com/archivetune-auth?nonce=owned&code=abc",
            "http://127.0.0.1:49153/archivetune-auth?nonce=owned&code=abc",
            "http://127.0.0.1:49152/other?nonce=owned&code=abc",
            "http://user@127.0.0.1:49152/archivetune-auth?nonce=owned&code=abc",
            "http://127.0.0.1:49152/archivetune-auth?nonce=owned&nonce=other&code=abc",
            "http://127.0.0.1:49152/archivetune-auth?nonce=owned&code=abc&code=def",
            "http://127.0.0.1:49152/archivetune-auth?nonce=owned&code=abc&error=access_denied",
            "http://127.0.0.1:49152/archivetune-auth?nonce=owned&code=",
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { discordCallbackCode(it, "owned") } }
    }
}
