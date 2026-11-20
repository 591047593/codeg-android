package app.codeg.android.core.update

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
}
