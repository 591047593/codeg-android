package app.codeg.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The coding agents codeg can drive. Wire value is snake_case (Rust serde
 * `rename_all = "snake_case"`).
 *
 * Kept free of any Compose/UI types so the model layer stays pure Kotlin — the
 * per-agent accent colours and monogram rendering live in the design system
 * (`AgentVisuals`), keyed off this enum.
 *
 * Unknown future agent types decode to [CLAUDE_CODE] (matching iOS) so one new
 * server-side agent can't break a whole list decode.
 */
@Serializable(with = AgentType.AgentTypeSerializer::class)
enum class AgentType(val wire: String) {
    CLAUDE_CODE("claude_code"),
    CODEX("codex"),
    OPEN_CODE("open_code"),
    GEMINI("gemini"),
    OPEN_CLAW("open_claw"),
    CLINE("cline"),
    HERMES("hermes"),
    CODE_BUDDY("code_buddy"),
    KIMI_CODE("kimi_code"),
    PI("pi"),
    GROK("grok"),
    CURSOR("cursor"),

    // The rest of the server's built-in set. These were absent, so every session on one
    // of them decoded through `fromWire`'s fallback as CLAUDE_CODE: the list showed a
    // Claude badge for a DeepSeek session, and `acp_connect` was called with
    // `agentType = claude_code` when resuming it — the wrong CLI, or a load failure.
    // Wire strings are `AgentType::as_wire` in the server's `models/agent.rs`.
    DEEPSEEK("deepseek"),
    QODER("qoder"),
    ANTIGRAVITY("antigravity"),
    KIRO("kiro"),
    ZCODE("zcode"),
    TRAE("trae"),
    COMMAND_CODE("command_code"),
    MINIMAX_CODE("minimax_code");

    val displayName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude Code"
            CODEX -> "Codex CLI"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini CLI"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi Code"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Antigravity"
            KIRO -> "Kiro"
            ZCODE -> "ZCode"
            TRAE -> "Trae"
            COMMAND_CODE -> "Command Code"
            MINIMAX_CODE -> "MiniMax Code"
        }

    /** Short label for dense badges. */
    val shortName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude"
            CODEX -> "Codex"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Antigravity"
            KIRO -> "Kiro"
            ZCODE -> "ZCode"
            TRAE -> "Trae"
            COMMAND_CODE -> "Command"
            MINIMAX_CODE -> "MiniMax"
        }

    companion object {
        /**
         * The agent for [raw], falling back to [CLAUDE_CODE] when unrecognised so a
         * single agent-typed field always decodes (matching iOS). List decoders that
         * must not collapse several unknown agents onto one identity should use
         * [knownFromWire] and drop the nulls instead — see `CodegClient.decodeAgentList`.
         */
        /** Prefix a user-registered ACP agent's wire value carries (`custom:<slug>`). */
        const val CUSTOM_PREFIX = "custom:"

        fun fromWire(raw: String): AgentType = knownFromWire(raw) ?: CLAUDE_CODE

        /** The agent for [raw], or null when this build doesn't recognise the wire value. */
        fun knownFromWire(raw: String): AgentType? = entries.firstOrNull { it.wire == raw }

        /**
         * True when [raw] names a user-registered ACP agent (`custom:<slug>`).
         *
         * Those have no enum entry — the server interns them at runtime from its own
         * registry — so a caller that must not collapse them onto [CLAUDE_CODE] checks
         * this first and keeps the raw slug.
         */
        fun isCustom(raw: String): Boolean = raw.startsWith(CUSTOM_PREFIX)

        /** The raw wire string for [raw] when it is a custom agent, else null. */
        fun customSlug(raw: String): String? =
            raw.takeIf { isCustom(it) }?.removePrefix(CUSTOM_PREFIX)?.takeIf { it.isNotEmpty() }
    }

    object AgentTypeSerializer : KSerializer<AgentType> {
        override val descriptor =
            PrimitiveSerialDescriptor("com.codeg.AgentType", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: AgentType) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): AgentType =
            fromWire(decoder.decodeString())
    }
}
