package app.codeg.android.core.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private fun release(digest: String) = """
        [{"tag_name":"build-200","draft":false,"prerelease":false,"body":"Notes","assets":[
          {"name":"app.apk","size":16384,"digest":$digest,
           "browser_download_url":"https://github.com/591047593/codeg-android/releases/download/build-200/app.apk"}
        ]}]
    """.trimIndent()

    @Test fun retainsAuthoritativeGithubAssetDigestForProxyVerification() {
        val hash = "ab".repeat(32)
        val update = UpdateChecker.parse(release("\"sha256:$hash\""), 199)!!
        assertEquals(hash, update.sha256)
        assertEquals(16384L, update.size)
    }

    @Test fun malformedDigestIsNotTreatedAsTrustedChecksum() {
        val update = UpdateChecker.parse(release("\"sha256:not-a-checksum\""), 199)!!
        assertNull(update.sha256)
    }

    @Test fun nullDigestKeepsCompatibilityWithLegacyReleaseMetadata() {
        assertNull(UpdateChecker.parse(release("null"), 199)!!.sha256)
    }

    @Test fun successfulNoUpdateIsAnEmptyResultNotAnError() = runBlocking {
        val client = HttpClient(MockEngine { respond("[]", HttpStatusCode.OK) }) { expectSuccess = true }
        UpdateChecker(client).use { assertNull(it.check(200, manual = true)) }
    }

    @Test fun foregroundCheckReportsNetworkFailureInsteadOfPretendingNoUpdate() = runBlocking {
        val client = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }) { expectSuccess = true }
        try {
            UpdateChecker(client).use { it.check(200, manual = true) }
            fail("HTTP failure must not look like no update")
        } catch (_: io.ktor.client.plugins.ServerResponseException) {}
    }
}
