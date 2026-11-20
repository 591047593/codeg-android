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
