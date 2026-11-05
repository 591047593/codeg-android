package app.codeg.android.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.core.data.InteractionKind
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.data.PendingInteraction
import app.codeg.android.core.common.LocaleManager
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation the user is LOOKING AT right now (detail on top), mirrored
 * here by the shell so the notifier can suppress the "agent needs you" alert
 * for the session already on screen (批v160 W8: 屏上抑制——UI 置顶无妨，仅通知
 * 抑制用它). null = not on a conversation detail.
 */
object ForegroundConversation {
    private val _ref = MutableStateFlow<SessionRef?>(null)
    val ref: StateFlow<SessionRef?> = _ref.asStateFlow()

    fun set(ref: SessionRef?) {
        _ref.value = ref
    }
}

/**
 * System notifications for "an agent is blocked waiting for you" (批v160 W8).
 * Pure projection of [InteractiveRequestWatcher.requests]: one notification PER
 * CONVERSATION (stable id → tap-cancel / auto-cancel / re-post all agree), the
 * body lists that conversation's pending requests. Suppressed — cancelled, not
 * just muted — while that conversation is the foreground detail; re-posted the
 * moment the user navigates away while it still waits. Disabled in settings →
 * everything cancelled and the collector idles.
 *
 * Notification ids must be STABLE ACROSS PROCESSES (cancel-after-tap and
 * resolve-after-restart both rely on it), hence the hash of (serverId,
 * conversationId), never a counter.
 */
@Singleton
class InteractiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watcher: InteractiveRequestWatcher,
    private val appSettingsStore: AppSettingsStore,
    @ApplicationScope appScope: CoroutineScope,
) {
    /** Conversations that currently have a notification out (group key → id). */
    private val posted = mutableMapOf<String, Int>()

    init {
        appScope.launch {
            combine(
                watcher.requests,
                appSettingsStore.settings,
                ForegroundConversation.ref,
            ) { requests, settings, fg ->
                // One bad render (e.g. a SecurityException from a just-revoked
                // permission) must not kill the process-lifetime collector.
                try {
                    render(requests, settings.notifyInteractiveEnabled, fg)
                } catch (_: Exception) {
                }
            }.collect()
        }
    }

    /** Idempotent; called from [app.codeg.android.CodegApplication.onCreate]. */
    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val interactive = NotificationChannel(
            CHANNEL_INTERACTIVE,
            LocaleManager.wrap(context).getString(R.string.notif_channel_interactive),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_interactive_desc)
        }
        val keepAlive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            LocaleManager.wrap(context).getString(R.string.notif_channel_keepalive),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_keepalive_desc)
        }
        runCatching { nm.createNotificationChannels(listOf(interactive, keepAlive)) }
    }

    private fun render(
        requests: List<PendingInteraction>,
        enabled: Boolean,
        fg: SessionRef?,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!enabled) {
            posted.keys.toList().forEach { key -> nm.cancel(posted.remove(key)!!) }
            return
        }
        val groups = requests.groupBy { "${it.serverId}|${it.conversationId}" }
        // Conversations whose last waiting request resolved: cancel the alert.
        posted.keys.filterNot { it in groups.keys }.toList().forEach { key ->
            nm.cancel(posted.remove(key)!!)
        }
        groups.forEach { (groupKey, items) ->
            val first = items.first()
            val id = notificationId(first.serverId, first.conversationId)
            // Viewing the conversation right now → the alert would be noise.
            if (fg == SessionRef(first.serverId, first.conversationId)) {
                if (groupKey in posted) {
                    nm.cancel(id)
                    posted.remove(groupKey)
                }
                return@forEach
            }
            runCatching { nm.notify(id, buildNotification(items)) }
            posted[groupKey] = id
        }
    }

    private fun buildNotification(items: List<PendingInteraction>): Notification {
        // The app language rides the custom LocaleManager, not the system config.
        val ctx = LocaleManager.wrap(context)
        val first = items.first()
        val lines = items.map { item ->
            val label = ctx.getString(kindLabel(item.kind))
            val title = item.title.ifBlank { label }
            "$label：$title"
        }
        val text = if (lines.size == 1) {
            "${lines[0]} · ${first.serverName}"
        } else {
            ctx.getString(R.string.notif_interactive_multi, lines.size)
        }
        // Deep link carries the owning device; the shell handoffs to it before
        // opening (conversation ids are server-local).
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("codeg://conversation/${first.conversationId}?serverId=${Uri.encode(first.serverId)}"),
            ctx,
            MainActivity::class.java,
        )
        val contentIntent = PendingIntent.getActivity(
            ctx,
            first.conversationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_INTERACTIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(first.serverName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(contentIntent)
            // Tap opens the conversation — the alert served its purpose.
            .setAutoCancel(true)
            // Re-posts while the same request still waits must stay silent (the
            // notification is refreshed on every relevant state change).
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    companion object {
        const val CHANNEL_INTERACTIVE = "interactive_requests"
        const val CHANNEL_KEEPALIVE = "service_keepalive"

        /** Stable per-conversation notification id (server-local conversation ids
         *  collide across devices — the device id joins the hash). */
        fun notificationId(serverId: String, conversationId: Int): Int =
            31 * serverId.hashCode() + conversationId

        fun kindLabel(kind: InteractionKind): Int = when (kind) {
            InteractionKind.PERMISSION -> R.string.workbench_kind_permission
            InteractionKind.QUESTION -> R.string.workbench_kind_question
            InteractionKind.PLAN -> R.string.workbench_kind_plan
        }
    }
}
