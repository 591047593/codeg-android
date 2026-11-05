package app.codeg.android.core.data

import app.codeg.android.core.datastore.ServerProfile

/**
 * Where one device stands in an aggregate fan-out (batch v137). The aggregate
 * feeds used to be all-or-nothing (`awaitAll`), so a powered-off device held the
 * whole first screen until its 10s deadline. Each device now commits its own
 * state as soon as it settles, and the UI derives its banner from these entries.
 */
enum class DeviceLoadStatus { LOADING, OK, FAILED }

/** One device's state inside an aggregate round. [error] is only set for FAILED. */
data class DeviceLoad(
    val serverId: String,
    val name: String,
    val status: DeviceLoadStatus,
    val error: String? = null,
)

/**
 * Per-device deadline for the aggregate feeds. Tailscale direct is usually
 * <200ms and DERP relay 300–800ms; devices commit independently, so a longer
 * deadline never stalls the list — it only delays one device's FAILED banner.
 * 10s (批v146 review): 3s marked healthy-but-cold relayed devices FAILED on
 * the first round, dropping rows the user then had to manually refresh.
 * Single-device pages get a slightly wider [SINGLE_DEVICE_TIMEOUT_MS] because
 * the user asked for that device explicitly.
 */
const val AGG_DEVICE_TIMEOUT_MS: Long = 10_000L
const val SINGLE_DEVICE_TIMEOUT_MS: Long = 12_000L

/**
 * Fleet M1: the optional attention-count round-trip gets its own, tighter
 * budget inside the device round — it rides the same envelope as the task list
 * but a missing count only degrades the badge, so a slow count must never eat
 * the list's deadline and fail the whole device slice.
 */
const val ATTENTION_COUNT_TIMEOUT_MS: Long = 3_000L

/**
 * How long an aggregate may stay completely empty before it stops showing the
 * full-screen spinner and paints the list frame with per-device "connecting"
 * placeholders instead.
 */
const val AGG_SKELETON_DELAY_MS: Long = 600L

/** Folder-name lookup gets its own, shorter budget inside the per-device deadline:
 *  a slow `/folders` must not cost the device's rows (batch v137 review #2). */
const val FOLDER_LOOKUP_TIMEOUT_MS: Long = 800L

/**
 * Liveness snapshot (`pet_list_active_sessions`) shares the same reasoning:
 * it only REFINES rows the feed already has, so a slow or missing answer degrades
 * to the persisted `status` flag instead of costing the device its rows.
 */
const val LIVE_PROBE_TIMEOUT_MS: Long = 2_000L

/**
 * 批v155 bugfix: how stale an `in_progress` row may be when the liveness probe
 * is UNAVAILABLE (timeout/old server) and the feed must fall back to the
 * persisted status flag alone. A genuinely running turn keeps writing messages,
 * so its `updated_at` advances; a row frozen longer than this is a corpse —
 * a turn killed mid-flight (server power-off / crash) never got its terminal
 * status write and would otherwise sit in 「运行中」 forever, resurfacing every
 * time one probe round flakes. Generous enough for a silent long tool call,
 * far below the multi-hour ghosts this kills.
 */
val RUNNING_STATUS_MAX_AGE: java.time.Duration = java.time.Duration.ofMinutes(60)

/**
 * 批v155(R2D): the liveness rule for every surface WITHOUT a probe round (the
 * feed's probe path lives in ActivityViewModel; chats/search/project counts
 * don't have one). "Running" requires the persisted flag AND a heartbeat —
 * a genuinely running turn writes messages, so `updated_at` advances; a row
 * frozen longer than [RUNNING_STATUS_MAX_AGE] is the power-off corpse that
 * never got its terminal status write. Cost of the gate: a running-but-silent
 * >60min turn loses its pulse/count until its next message — the same
 * trade-off the feed's probe-failed fallback already makes.
 */
fun app.codeg.android.core.model.ConversationSummary.isLiveFresh(
    now: java.time.Instant = java.time.Instant.now(),
): Boolean = status.isLive && updatedAt.isAfter(now.minus(RUNNING_STATUS_MAX_AGE))

/** Names of the devices that failed this round, in feed order. */
fun List<DeviceLoad>.failedNames(): List<String> =
    filter { it.status == DeviceLoadStatus.FAILED }.map { it.name }

/** Names of the devices still being fetched this round, in feed order. */
fun List<DeviceLoad>.loadingNames(): List<String> =
    filter { it.status == DeviceLoadStatus.LOADING }.map { it.name }

/** How many devices returned data this round. */
fun List<DeviceLoad>.okCount(): Int = count { it.status == DeviceLoadStatus.OK }

/**
 * Drop devices that no longer participate in the aggregate (hidden, skipped or
 * deleted). A retry seeds from the previous round, so without this a device that
 * was skipped mid-round would linger as FAILED forever and keep naming itself in
 * the banner.
 */
fun List<DeviceLoad>.participating(ids: Set<String>): List<DeviceLoad> =
    filter { it.serverId in ids }

/**
 * Devices a retry must re-ask: everything that has not settled OK. Including a
 * still-LOADING device is deliberate — the retry supersedes the running round,
 * so leaving it out would cancel its fetch and never issue a new one.
 */
fun List<DeviceLoad>.retryableIds(): Set<String> =
    filter { it.status != DeviceLoadStatus.OK }.map { it.serverId }.toSet()

/**
 * Put every device that has not settled OK back into LOADING for a retry, leaving
 * the healthy ones untouched — the banner's retry must not re-ask devices that
 * already answered, but must not strand one that is still inside its window when
 * the retry supersedes the round either.
 */
fun List<DeviceLoad>.retrying(retryIds: Set<String>): List<DeviceLoad> =
    map { load ->
        if (load.serverId in retryIds) load.copy(status = DeviceLoadStatus.LOADING, error = null) else load
    }

/**
 * Identity of the aggregate fan-out set. Name/baseUrl are included so a rename
 * or endpoint edit re-fans-out, and includeInAll so toggling "skip in All"
 * re-computes immediately instead of waiting for the next 25s poll.
 */
fun List<ServerProfile>.aggregateDeviceSignature(): String =
    filter { it.visible }
        .joinToString("|") { "${it.id}:${it.name}:${it.baseUrl}:${it.includeInAll}" }
