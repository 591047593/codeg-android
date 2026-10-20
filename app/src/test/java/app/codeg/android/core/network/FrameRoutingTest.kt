package app.codeg.android.core.network

import app.codeg.android.core.model.AcpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the WebSocket frame router (dual-layer channel vs attach protocol). */
class FrameRoutingTest {

    private val json = CodegJson.response
    private fun parse(raw: String): StreamFrame? = EventStream.parseFrame(raw, json)

    @Test
    fun `legacy ready channel maps to Ready`() {
        assertEquals(StreamFrame.Ready, parse("""{"channel":"__ready__","payload":null}"""))
    }

    @Test
    fun `other legacy channels are ignored`() {
        assertNull(parse("""{"channel":"conversation://changed","payload":{}}"""))
    }

    @Test
    fun `event frame unwraps the envelope`() {
        val frame = parse(
            """{"type":"event","subscription_id":"s","envelope":
               {"seq":1,"connection_id":"c","type":"content_delta","text":"hello"}}""",
        )
        assertTrue(frame is StreamFrame.Event)
        val event = (frame as StreamFrame.Event).envelope.event
        assertEquals(AcpEvent.ContentDelta("hello"), event)
    }

    @Test
    fun `pong and detached route correctly`() {
        assertEquals(StreamFrame.Pong, parse("""{"type":"pong"}"""))
        assertEquals(StreamFrame.Detached("lagged"), parse("""{"type":"detached","reason":"lagged"}"""))
    }

    @Test
    fun `garbage and unknown frames are dropped`() {
        assertNull(parse("not json"))
        assertNull(parse("""{"type":"mystery"}"""))
    }

    @Test
    fun `websocket url derives ws and wss with the events path`() {
        assertEquals("ws://host:3080/ws/events", EventStream.websocketUrl("http://host:3080"))
        assertEquals("wss://host/ws/events", EventStream.websocketUrl("https://host/"))
    }

    @Test
    fun `websocket url supplies a scheme for a bare host`() {
        // The settings screen accepts `host:port`; the derived socket must match the
        // same default the HTTP client uses instead of silently picking ws or wss.
        assertEquals("ws://192.168.1.10:3080/ws/events", EventStream.websocketUrl("192.168.1.10:3080"))
        assertEquals("wss://codeg.example.com/ws/events", EventStream.websocketUrl("https://codeg.example.com"))
    }
}
