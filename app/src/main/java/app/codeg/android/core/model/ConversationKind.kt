package app.codeg.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a conversation row is, as the server's `ConversationKind` reports it.
 *
 * The distinction that matters on this screen is [CHAT] versus [REGULAR]: a "chat" row is
 * the hidden scratch conversation the desktop client keeps per folder, and before this was
 * decoded the list had no way to tell it from a real project conversation — every scratch
 * row surfaced as its own sidebar group named "Chat" with a single conversation in it.
 */
@Serializable(with = ConversationKind.Serializer::class)
enum class ConversationKind(val wire: String) {
    /** A normal project conversation. */
    REGULAR("regular"),
    /** A scratch chat, not shown in the folder tree. */
    CHAT("chat"),
    /** A row owned by the loop/automation runner. */
    LOOP("loop"),
    /** A sub-session a parent conversation delegated to. */
    DELEGATE("delegate"),

    /** A value from a newer server; kept rather than collapsed onto [REGULAR]. */
    OTHER("other");

    /** True for rows that belong in the folder tree (everything but a scratch chat). */
    val isListed: Boolean get() = this != CHAT

    object Serializer : KSerializer<ConversationKind> {
        override val descriptor =
            PrimitiveSerialDescriptor("com.codeg.ConversationKind", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ConversationKind) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): ConversationKind =
            fromWire(decoder.decodeString())
    }

    companion object {
        fun fromWire(raw: String?): ConversationKind {
            if (raw == null) return REGULAR
            return entries.firstOrNull { it.wire == raw } ?: OTHER
        }
    }
}
