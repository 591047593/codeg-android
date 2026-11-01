package app.codeg.android.core.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批v149 会话模式派发的"首条指令"交接站。收件箱以会话方式派发成功后：
 * ① 服务端 create_conversation 已返回会话 id；② UI 自动跳转该会话；
 * ③ 会话详情页 load 完成后在 [SessionDetailViewModel] 里消费这里存的首条
 * 指令并直接发送——用户看到的效果是"派发完直接落在正在干活的会话里"。
 *
 * 为什么是进程内存而非 nav 参数/DataStore：
 * - nav 参数会把指令写进回退栈，进程恢复后同一 entry 重建可能重发同一条
 *   指令（回复已完成时 isInFlight 守不住）；
 * - 持久化同理，复活一条可能已送达的指令违背"结果未知绝盲目重发"的底线。
 *   内存槽在进程死亡时消失——丢失方向永远安全（用户可从想法记录重派）。
 * 单槽即够：一次派发只产生一个待发指令，跳转是紧随其后的下一步。
 */
@Singleton
class SessionAutoStart @Inject constructor() {

    data class Pending(val serverId: String, val conversationId: Int, val prompt: String)

    private var pending: Pending? = null

    /** Arm only with a real prompt — an empty instruction must never fire a send. */
    @Synchronized
    fun arm(serverId: String, conversationId: Int, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        pending = Pending(serverId, conversationId, trimmed)
    }

    /**
     * Consume-once, exact-match: a different conversation (or a re-entry after
     * the match was already consumed) reads null. Consuming on a LIVE session is
     * the caller's decision — take() always clears so an armed prompt can never
     * fire from a later, unrelated visit to the same session.
     */
    @Synchronized
    fun take(serverId: String, conversationId: Int): String? {
        val p = pending ?: return null
        if (p.serverId != serverId || p.conversationId != conversationId) return null
        pending = null
        return p.prompt
    }
}
