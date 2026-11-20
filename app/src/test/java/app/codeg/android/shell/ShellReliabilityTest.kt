package app.codeg.android.shell

import app.codeg.android.core.update.UpdateCheckPolicy
import app.codeg.android.shell.error.PageRetryGate
import app.codeg.android.shell.server.ServerProbeOutcome
import app.codeg.android.shell.server.classifyServerProbe
import org.junit.Assert.*
import org.junit.Test

class ShellReliabilityTest {
    @Test fun successfulUpdateCheckKeepsLongCooldown() {
        assertFalse(UpdateCheckPolicy.shouldCheck(2_000_000, 1_999_000, 0, false))
    }
    @Test fun failedCheckCanRetrySoonInsteadOfWaitingSixHours() {
        assertTrue(UpdateCheckPolicy.shouldCheck(2_000_000, 0, 1_900_000, false))
        assertFalse(UpdateCheckPolicy.shouldCheck(2_000_000, 0, 1_999_000, false))
    }
    @Test fun manualUpdateCheckBypassesAutomaticCooldown() {
        assertTrue(UpdateCheckPolicy.shouldCheck(2_000_000, 1_999_000, 1_999_000, true))
    }
    @Test fun recoveryNeverStartsTwoPageLoadsAtOnce() {
        val gate = PageRetryGate()
        assertTrue(gate.begin())
        repeat(20) { assertFalse(gate.begin()) }
        gate.finish()
        assertTrue(gate.begin())
    }
    @Test fun staleNavigationCallbacksCannotReleaseTheCurrentLoad() {
        val gate = PageRetryGate()
        val a = "https://codeg.test/a"
        val b = "https://codeg.test/b"
        val oldGeneration = gate.start(a)
        val currentGeneration = gate.start(b)
        assertTrue(currentGeneration > oldGeneration)
        assertEquals(b, gate.activeUrl)
        // The finish, network-error and HTTP-error paths all require acceptance
        // before cancelling a timeout, changing an error or releasing the gate.
        assertFalse(gate.acceptsCompletion(a, b, 100))
        repeat(2) { assertFalse(gate.accepts(a)) }
        assertTrue(gate.isLoading)
        assertFalse(gate.begin())
        assertTrue(gate.owns(currentGeneration))
        assertFalse(gate.owns(oldGeneration))
        assertTrue(gate.acceptsCompletion(b, b, 100))
        gate.finish()
        assertFalse(gate.isLoading)
        assertTrue(gate.begin())
    }
    @Test fun fragmentChangesKeepMainDocumentOwnershipButQueriesDoNot() {
        val gate = PageRetryGate()
        gate.start("https://codeg.test/workspace?session=a#one")
        assertTrue(gate.accepts("https://codeg.test/workspace?session=a#two"))
        assertTrue(gate.acceptsCompletion("https://codeg.test/workspace?session=a#one",
            "https://codeg.test/workspace?session=a#two", 100))
        assertFalse(gate.accepts("https://codeg.test/workspace?session=b#one"))
    }
    @Test fun identicalUrlRetryAdvancesTimeoutOwnershipAndRequiresCompletion() {
        val gate = PageRetryGate()
        val url = "https://codeg.test/workspace"
        val oldGeneration = gate.start(url)
        gate.finish()
        assertTrue(gate.begin())
        assertFalse(gate.owns(oldGeneration)) // Also before onPageStarted arrives.
        val generation = gate.start(url)
        assertFalse(gate.owns(oldGeneration))
        assertTrue(gate.owns(generation))
        assertFalse(gate.acceptsCompletion(url, url, 50))
        assertFalse(gate.acceptsCompletion(url, "https://codeg.test/other", 100))
        // No callback ID exists for identical URLs: keep real network errors
        // reportable even while progress is below 100 or WebView.url is old.
        assertTrue(gate.accepts(url))
        assertTrue(gate.acceptsCompletion(url, url, 100))
    }

    @Test fun recognizableWebPageIsReadyButArbitraryHtmlIsNot() {
        assertEquals(ServerProbeOutcome.READY, classifyServerProbe(200, "text/html", "<html><title>Codeg</title></html>"))
        assertEquals(ServerProbeOutcome.UNRECOGNIZED, classifyServerProbe(200, "text/html", "<html><title>Other site</title></html>"))
    }
    @Test fun authorizationAndHttpFailuresAreNotSilentSuccesses() {
        for (code in listOf(401, 403, 302)) assertEquals(ServerProbeOutcome.AUTH_REQUIRED, classifyServerProbe(code, "", ""))
        assertEquals(ServerProbeOutcome.NOT_FOUND, classifyServerProbe(404, "", ""))
        assertEquals(ServerProbeOutcome.SERVER_ERROR, classifyServerProbe(503, "", ""))
    }
}
