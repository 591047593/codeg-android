package app.codeg.android.core.notify

import app.codeg.android.core.data.InteractionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 批v160 W8: the notification id is the cross-process contract — tap-cancel
 * (autoCancel) and a resolve-after-restart both cancel by the SAME id the post
 * used, so it must be a pure function of (serverId, conversationId), never a
 * counter. String.hashCode is specified-deterministic on the JVM, which is what
 * makes the formula stable across processes; these tests pin that nobody swaps
 * it for something process-local.
 */
class InteractiveNotifierTest {

    @Test
    fun `id is deterministic for the same conversation`() {
        assertEquals(
            InteractiveNotifier.notificationId("a", 5),
            InteractiveNotifier.notificationId("a", 5),
        )
    }

    @Test
    fun `cross-device same conversation id is a different notification`() {
        // Server-local ids collide across devices (the whole reason the server
        // id joins the hash) — cancelling device B's alert must never take down
        // device A's.
        assertNotEquals(
            InteractiveNotifier.notificationId("a", 5),
            InteractiveNotifier.notificationId("b", 5),
        )
    }

    @Test
    fun `different conversations on one device differ`() {
        assertNotEquals(
            InteractiveNotifier.notificationId("a", 5),
            InteractiveNotifier.notificationId("a", 6),
        )
    }

    @Test
    fun `kind labels resolve to distinct resources for every kind`() {
        val labels = InteractionKind.entries.map { InteractiveNotifier.kindLabel(it) }
        assertEquals(InteractionKind.entries.size, labels.toSet().size)
    }
}
