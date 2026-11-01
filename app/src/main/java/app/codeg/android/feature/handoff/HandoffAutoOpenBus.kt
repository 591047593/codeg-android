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

    fun fire(serverId: String, conversationId: Int) {
        pending.value = "$serverId:$conversationId"
    }

    fun consume() {
        pending.value = null
    }
}
