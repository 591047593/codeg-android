package app.codeg.android.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.common.rememberNowInstant
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.statusColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationSummary
import androidx.compose.ui.res.stringResource

/**
 * One conversation row in the Chats / Activity / Search lists: a single
 * borderless line. The agent's brand avatar (with a status-tinted dot) anchors
 * the left, the title fills the middle, and the trailing edge shows a live pulse
 * while running or a compact relative time otherwise. Long-press opens a Pin/
 * Unpin menu (the Compose analogue of the iOS row context menu).
 *
 * Faithful port of the iOS `SessionRow`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    conversation: ConversationSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    selected: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    // Relative time needs a clock, not `Instant.now()` inside composition (which is
    // non-idempotent and froze the label until something else forced a recomposition).
    val now by rememberNowInstant()

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (selected) Modifier.background(colors.accent.copy(alpha = 0.18f)) else Modifier,
                )
                .combinedClickable(
                    onClick = onClick,
                    // Any menu action makes the row long-pressable — not just pin. Gating
                    // this on `onTogglePin` meant a screen that passed only `onDelete`
                    // (the activity feed, search results) bound no long-press at all, so
                    // its menu could never open.
                    onLongClick = if (onTogglePin != null || onDelete != null) {
                        { menuOpen = true }
                    } else {
                        null
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box {
                AgentAvatar(conversation.agentType, size = 26.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor(conversation.status))
                        .border(1.5.dp, colors.bg, CircleShape),
                )
            }

            // Two-line layout: the title, then a quiet metadata line. A single-line row
            // gave every conversation the same visual weight and carried almost no
            // information — you could not tell when it last ran, how big it was, or which
            // model it used without opening it. The metadata line is what makes the list
            // scannable, and it also separates a row from its section header at a glance.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = conversation.trimmedTitle ?: stringResource(R.string.session_untitled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (folderName != null) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(10.dp),
                        )
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                        MetaDot()
                    }
                    conversation.model?.takeIf { it.isNotBlank() }?.let { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp),
                        )
                        MetaDot()
                    }
                    Text(
                        text = when {
                            conversation.status.isLive -> stringResource(R.string.session_status_live)
                            else -> RelativeTime.compact(
                                conversation.updatedAt,
                                now = now,
                                justNow = stringResource(R.string.time_just_now),
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.status.isLive) colors.accent else colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }

            if (conversation.status.isLive) {
                LivePulse()
            } else if (conversation.isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        if (onTogglePin != null || onDelete != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onTogglePin != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (conversation.isPinned) {
                                    stringResource(R.string.session_unpin)
                                } else {
                                    stringResource(R.string.session_pin)
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTogglePin?.invoke()
                        },
                    )
                }
                // Deleting from the list saves a trip through the detail screen's
                // overflow menu. The caller confirms before acting.
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = colors.danger) },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = colors.danger)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** The 2dp separator between metadata items on a session row's second line. */
@Composable
private fun MetaDot() {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .size(2.dp)
            .clip(CircleShape)
            .background(colors.textTertiary.copy(alpha = 0.5f)),
    )
}
