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
 *
 * 批v150 P0：发射端接线补全前，arm 只在 probe 恢复 CONFIRMED 的路径也可能
 * 发生（用户早已离开派发界面）。为防一条滞留槽位在用户很久之后手动打开同一
 * 会话时突然发射，[Pending] 带武装时刻，[take] 拒绝超过 [STALE_MS] 的滞留
 * 指令——旧指令宁可不发（用户可从想法记录重派），也不迟到一个错愕的时机。
 */
@Singleton
class SessionAutoStart @Inject constructor() {

    data class Pending(
        val serverId: String,
        val conversationId: Int,
        val prompt: String,
        val armedAtEpochMs: Long,
        /**
         * 批v154 附件：派发时随指令上传到目标设备工作目录的文件。
         * 路径由服务端 `upload_workspace_file` 返回的绝对路径组成，会话详情页
         * 把它拼成 [FileAttachment] 挂在首条消息上（同手动附件走同一条发送链）。
         */
        val files: List<UploadedAttachment> = emptyList(),
    )

    /** 可注入时钟：单测把时间拨快，生产用墙钟（10 分钟窗对 NTP 跳变不敏感）。 */
    internal var clock: () -> Long = System::currentTimeMillis

    private var pending: Pending? = null

    /**
     * Arm only with real payload — an empty instruction (no text, no files) must
     * never fire a send. Files ride along: a dispatch of only images still counts.
     */
    @Synchronized
    fun arm(
        serverId: String,
        conversationId: Int,
        prompt: String,
        files: List<UploadedAttachment> = emptyList(),
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() && files.isEmpty()) return
        pending = Pending(serverId, conversationId, trimmed, clock(), files)
    }

    /**
     * Consume-once, exact-match: a different conversation (or a re-entry after
     * the match was already consumed) reads null. Consuming on a LIVE session is
     * the caller's decision — take() always clears so an armed prompt can never
     * fire from a later, unrelated visit to the same session. A slot older than
     * [STALE_MS] is consumed AND dropped: stale never fires.
     */
    @Synchronized
    fun take(serverId: String, conversationId: Int): String? = takePending(serverId, conversationId)?.prompt

    /** Same consume-once contract as [take], but hands back the armed files too. */
    @Synchronized
    fun takePending(serverId: String, conversationId: Int): Pending? {
        val p = pending ?: return null
        if (p.serverId != serverId || p.conversationId != conversationId) return null
        pending = null
        if (clock() - p.armedAtEpochMs > STALE_MS) return null
        return p
    }

    companion object {
        /**
         * An armed prompt older than this is stale and never fires. The normal
         * path is seconds (confirm → jump → load → send); this window only has
         * to cover a probe-resolution the user walks away from — long enough to
         * never eat a legitimate send, short enough that "why is my old prompt
         * sending NOW" can't happen.
         */
        const val STALE_MS = 10 * 60_000L
    }
}
