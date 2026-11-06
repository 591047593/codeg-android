package app.codeg.android.feature.usage

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot「打开用量页」请求总线：动态工作台的「今日用量」统计卡 fire，
 * Settings 的 USAGE leaf 消费（compareAndSet 单次消费 + 10s 新鲜窗，镜像
 * ServerRepository.requestAutomationTab 的既有模式）。
 */
object UsageOpenBus {
    private val pending = MutableStateFlow(0L)

    fun request() { pending.value = System.currentTimeMillis() }

    /** True at most once per request, within [FRESHNESS_MS] of it. */
    fun consume(): Boolean {
        val ts = pending.getAndSet(0L)
        return ts != 0L && System.currentTimeMillis() - ts <= FRESHNESS_MS
    }

    private const val FRESHNESS_MS = 10_000L
}
