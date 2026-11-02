package app.codeg.android.feature.handoff

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-screen "auto-open the handoff sheet on the session you just jumped to"
 * channel (批v150 §1.4 快捷会话交接): the picker fires [fire] right after the
 * owning-device handoff lands; [app.codeg.android.feature.sessiondetail.
 * SessionDetailScreen] collects [pending] and expands its HandoffSheet when the
 * key matches THIS session. Same shape as FloatingBallSettingsBus / DeepLinkBus.
 * The consumer resets the flag after consuming.
 */
object HandoffAutoOpenBus {
    /** "serverId:conversationId" awaiting auto-open, or null. */
    val pending = MutableStateFlow<String?>(null)

    /** When [fire] last ran — an unclaimed intent EXPIRES (SessionAutoStart
     *  同款 staleness 家族): a pending that outlives [FRESH_MS] must never
     *  ambush the user by auto-opening a sheet minutes/hours later. */
    val firedAtEpochMs = MutableStateFlow(0L)

    fun fire(serverId: String, conversationId: Int) {
        firedAtEpochMs.value = System.currentTimeMillis()
        pending.value = "$serverId:$conversationId"
    }

    fun consume() {
        pending.value = null
    }

    fun isFresh(): Boolean = System.currentTimeMillis() - firedAtEpochMs.value < FRESH_MS

    private const val FRESH_MS = 60_000L
}
