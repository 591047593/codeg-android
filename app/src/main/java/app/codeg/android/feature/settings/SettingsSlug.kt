package app.codeg.android.feature.settings

/**
 * Maps a `codeg://settings/<slug>` path segment onto a stable settings identifier.
 *
 * Kept separate from [SettingsLeaf] (which carries `@StringRes` + `ImageVector` and so
 * cannot be exercised by a plain JVM unit test) and from the route parsing in
 * `DeepLink`, so the accepted vocabulary is testable and documented in one place.
 *
 * Returns `null` for an unknown slug — the caller then simply opens the settings root
 * rather than failing.
 */
internal object SettingsSlug {
    /** Canonical, wire-facing slugs for each settings screen. */
    const val APPEARANCE = "appearance"
    const val LANGUAGE = "language"
    const val GENERAL = "general"
    const val QUICK_MESSAGES = "quick_messages"
    const val AGENTS = "agents"
    const val MODEL_PROVIDERS = "model_providers"
    const val EXPERTS = "experts"
    const val SKILLS = "skills"
    const val MCP = "mcp"
    const val VERSION_CONTROL = "version_control"
    const val CHAT_CHANNELS = "chat_channels"
    const val SYSTEM = "system"
    const val OPEN_SOURCE_LICENSES = "open_source_licenses"
    const val ABOUT = "about"

    /** Every canonical slug, in navigation order. */
    val all: List<String> = listOf(
        APPEARANCE, LANGUAGE, GENERAL, QUICK_MESSAGES,
        AGENTS, MODEL_PROVIDERS, EXPERTS, SKILLS, MCP,
        VERSION_CONTROL, CHAT_CHANNELS,
        SYSTEM, OPEN_SOURCE_LICENSES, ABOUT,
    )

    /**
     * Resolve [raw] to a canonical slug, accepting the natural spelling variants a
     * link author would use (`version-control`, `models`, `licenses`, ...).
     */
    fun resolve(raw: String?): String? {
        val normalized = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
            ?: return null
        if (normalized.isEmpty()) return null
        if (normalized in all) return normalized
        return when (normalized) {
            "theme", "color", "colors", "accent" -> APPEARANCE
            "lang", "locale" -> LANGUAGE
            "quickmessages", "quick_message", "messages" -> QUICK_MESSAGES
            "agent" -> AGENTS
            "models", "providers", "model_provider", "modelproviders" -> MODEL_PROVIDERS
            "expert" -> EXPERTS
            "skill" -> SKILLS
            "mcp_servers", "mcpservers" -> MCP
            "git", "versioncontrol", "vcs" -> VERSION_CONTROL
            "channels", "chat", "chat_channel" -> CHAT_CHANNELS
            "licenses", "license", "open_source", "opensource" -> OPEN_SOURCE_LICENSES
            "about_app", "version", "info" -> ABOUT
            else -> null
        }
    }
}
