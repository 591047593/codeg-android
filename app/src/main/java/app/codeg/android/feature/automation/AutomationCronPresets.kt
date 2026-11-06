package app.codeg.android.feature.automation

/**
 * Cron 预设（PRD M2 七档：每天/工作日/每周一/每月1号/每小时/每30分钟/自定义）。
 * The wire cron is 5-field `min hour dom mon dow` in UI numbering (0=Sunday);
 * the server remaps numbering and prepends seconds itself. The client NEVER
 * evaluates or converts a cron — [buildFor] only emits the five fields, and
 * `automation_compute_next_run` is the sole judge of validity/next fire.
 */
enum class CronPreset {
    DAILY,
    WORKDAYS,
    MONDAYS,
    MONTHLY_FIRST,
    HOURLY,
    EVERY_30_MIN,
    CUSTOM,
}

/** True for presets that fire at a fixed wall-clock time (the editor shows a
 *  时:分 picker for these; the interval presets have no time-of-day). */
val CronPreset.needsTimeOfDay: Boolean
    get() = this != CronPreset.HOURLY && this != CronPreset.EVERY_30_MIN

/**
 * Build the 5-field cron string for a preset. [minute]/[hour] are the
 * time-of-day for [CronPreset.needsTimeOfDay] presets; [custom] is the raw
 * 自定义 text. Returns null when 自定义 is blank.
 */
fun CronPreset.buildFor(minute: Int, hour: Int, custom: String): String? = when (this) {
    CronPreset.DAILY -> "$minute $hour * * *"
    CronPreset.WORKDAYS -> "$minute $hour * * 1-5"
    CronPreset.MONDAYS -> "$minute $hour * * 1"
    CronPreset.MONTHLY_FIRST -> "$minute $hour 1 * *"
    CronPreset.HOURLY -> "0 * * * *"
    CronPreset.EVERY_30_MIN -> "*/30 * * * *"
    CronPreset.CUSTOM -> custom.trim().takeIf { it.isNotEmpty() }
}

/**
 * Loose shape gate for the 自定义 field — five whitespace-separated fields
 * drawn from the standard cron alphabet. This only rejects obvious garbage
 * before the (authoritative) server round-trip; a plausible-but-invalid
 * expression still passes here and is caught by compute_next_run == null.
 */
fun isPlausibleCron(text: String): Boolean {
    val fields = text.trim().split(Regex("\\s+"))
    if (fields.size != 5) return false
    return fields.all { field ->
        field.isNotEmpty() && field.all { it.isDigit() || it in "*,-/" }
    }
}

/**
 * Reverse-map a stored cron back to the closest editor preset (the edit sheet
 * needs to highlight something). Exact string match on the canonical interval
 * forms; "M H …" shape match for the fixed-time presets; anything else is 自定义.
 */
fun matchCronPreset(cron: String?): CronPreset {
    val c = cron?.trim() ?: return CronPreset.DAILY
    when (c) {
        "0 * * * *" -> return CronPreset.HOURLY
        "*/30 * * * *" -> return CronPreset.EVERY_30_MIN
    }
    val parts = c.split(Regex("\\s+"))
    if (parts.size != 5) return CronPreset.CUSTOM
    val (minStr, hourStr, dom, mon, dow) = parts
    if (mon != "*") return CronPreset.CUSTOM
    val minute = minStr.toIntOrNull() ?: return CronPreset.CUSTOM
    val hour = hourStr.toIntOrNull() ?: return CronPreset.CUSTOM
    if (minute !in 0..59 || hour !in 0..23) return CronPreset.CUSTOM
    return when {
        dom == "*" && dow == "*" -> CronPreset.DAILY
        dom == "*" && dow == "1-5" -> CronPreset.WORKDAYS
        dom == "*" && dow == "1" -> CronPreset.MONDAYS
        dom == "1" && dow == "*" -> CronPreset.MONTHLY_FIRST
        else -> CronPreset.CUSTOM
    }
}

/** Parse "min hour …" fields back to (minute, hour) for the time-of-day picker;
 *  null when not a fixed-time shape (interval/custom presets). */
fun cronTimeOfDay(cron: String?): Pair<Int, Int>? {
    val parts = cron?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size != 5) return null
    val minute = parts[0].toIntOrNull() ?: return null
    val hour = parts[1].toIntOrNull() ?: return null
    if (minute !in 0..59 || hour !in 0..23) return null
    return minute to hour
}
