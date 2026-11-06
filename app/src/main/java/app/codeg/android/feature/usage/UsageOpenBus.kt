package app.codeg.android.feature.usage


/**
 * One-shot「打开用量页」请求总线：动态工作台的「今日用量」统计卡 fire，
 * Settings 的 USAGE leaf 消费（getAndSet 单次消费 + 10s 新鲜窗，镜像
 * ServerRepository.requestAutomationTab 的既有模式；elapsedRealtime 计时，
 * 墙钟跳变不影响新鲜窗判定）。
 */
object UsageOpenBus {
    private val pending = java.util.concurrent.atomic.AtomicLong(0L)

    fun request() { pending.set(android.os.SystemClock.elapsedRealtime()) }

    /** True at most once per request, within [FRESHNESS_MS] of it. */
    fun consume(): Boolean {
        val ts = pending.getAndSet(0L)
        return ts != 0L && android.os.SystemClock.elapsedRealtime() - ts <= FRESHNESS_MS
    }

    private const val FRESHNESS_MS = 10_000L
}
