package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The event cursor decides whether a streamed event is applied, and it is what the next
 * re-attach sends as `since_seq`. Both failure modes are silent:
 *
 * - too permissive — a replay/event overlap re-appends a text delta, and the paragraph
 *   shows up twice in the reply;
 * - too strict — an event at the cursor boundary is dropped and the reply has a hole.
 */
class EventCursorTest {

    @Test
    fun `an event ahead of the cursor advances it`() {
        assertEquals(EventCursor.ADVANCE(7), eventCursorDecision(sequence = 7, cursor = 3))
        assertEquals(EventCursor.ADVANCE(4), eventCursorDecision(sequence = 4, cursor = 3))
    }

    @Test
    fun `the first event on a fresh turn advances from zero`() {
        // connectFresh() resets the cursor to 0; the server numbers from 1.
        assertEquals(EventCursor.ADVANCE(1), eventCursorDecision(sequence = 1, cursor = 0))
    }

    @Test
    fun `an event exactly at the cursor is a duplicate`() {
        // The boundary case: it has already been applied, re-applying it duplicates text.
        assertEquals(EventCursor.DUPLICATE, eventCursorDecision(sequence = 5, cursor = 5))
    }

    @Test
    fun `an event behind the cursor is a duplicate`() {
        // A replay frame delivered out of order must not rewind the cursor or re-append.
        assertEquals(EventCursor.DUPLICATE, eventCursorDecision(sequence = 2, cursor = 9))
    }

    @Test
    fun `an unsequenced frame is applied without touching the cursor`() {
        // Some frames carry no cursor; they must still be applied, and must not reset it.
        assertEquals(EventCursor.APPLY_UNSEQUENCED, eventCursorDecision(sequence = 0, cursor = 12))
        assertEquals(EventCursor.APPLY_UNSEQUENCED, eventCursorDecision(sequence = -1, cursor = 12))
        assertEquals(EventCursor.APPLY_UNSEQUENCED, eventCursorDecision(sequence = 0, cursor = 0))
    }

    @Test
    fun `a run of in-order events advances monotonically`() {
        var cursor = 0L
        val applied = mutableListOf<Long>()
        for (seq in 1L..5L) {
            when (val d = eventCursorDecision(seq, cursor)) {
                is EventCursor.ADVANCE -> { cursor = d.sequence; applied += seq }
                EventCursor.DUPLICATE, EventCursor.APPLY_UNSEQUENCED -> Unit
            }
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), applied)
        assertEquals(5L, cursor)
    }

    @Test
    fun `a replayed overlap does not re-apply events`() {
        // Cursor at 5 (events 1..5 applied), then a replay re-delivers 3..7.
        var cursor = 5L
        val applied = mutableListOf<Long>()
        for (seq in 3L..7L) {
            when (val d = eventCursorDecision(seq, cursor)) {
                is EventCursor.ADVANCE -> { cursor = d.sequence; applied += seq }
                EventCursor.DUPLICATE, EventCursor.APPLY_UNSEQUENCED -> Unit
            }
        }
        // 6 and 7 were genuinely new; 3..5 were the overlap.
        assertEquals(listOf(6L, 7L), applied)
        assertEquals(7L, cursor)
    }
}
