package app.codeg.android.shell.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Actual Kotlin origin serialization; JVM execution is deferred to coordinator CI (no local Gradle). */
class NotificationOriginTest {
    @Test
    fun browserCompatibleOrigins() {
        val cases = mapOf(
            "http://EXAMPLE.com:80/path?q=1#fragment" to "http://example.com",
            "https://EXAMPLE.com:443/path" to "https://example.com",
            "https://example.com:8443/path" to "https://example.com:8443",
            "http://[::1]:80/path" to "http://[::1]",
            "https://[2001:0DB8:0:0:0:0:0:1]:443/path" to "https://[2001:db8::1]",
            "http://[::1]:3000/path" to "http://[::1]:3000",
            "https://bücher.example/path" to "https://xn--bcher-kva.example",
        )
        cases.forEach { (url, expected) -> assertEquals(url, expected, serverOrigin(url)) }
    }

    @Test
    fun rejectsMissingAndNonWebOrigins() {
        listOf(null, "", "file:///tmp/page.html", "javascript:alert(1)", "not a URL")
            .forEach { assertNull(it, serverOrigin(it)) }
    }
}
