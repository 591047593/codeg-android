package app.codeg.android.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.common.rememberNowInstant
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.statusColor
import app.codeg.android.core.designsystem.component.CodegMenu
import app.codeg.android.core.designsystem.component.CodegMenuItem
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
    /** Draw the inset hairline under this row (all but the last in a grouped card). */
    showDivider: Boolean = false,
    /** Indent level for a delegation child; `0` for a root conversation. */
    depth: Int = 0,
    /** Whether this row's delegation children are currently shown. */
    expanded: Boolean = false,
    /** Whether the children are being fetched, so the chevron can show a spinner. */
    loadingChildren: Boolean = false,
    /** Invoked when a row with children is tapped on its chevron. */
    onToggleChildren: (() -> Unit)? = null,
    /**
     * Corner shape for the row's own background.
     *
     * Standalone callers (search results, the activity feed) want a full pill per row; a
     * grouped list passes a shape that rounds only the first and last. Done as a parameter
     * rather than an internal constant so the caller owns the grouping geometry — an
     * inner clip would otherwise cut every row back to the same shape.
     */
    shape: Shape = RoundedCornerShape(16.dp),
    /**
     * Fill behind the row. [Color.Unspecified] leaves it transparent (search results and
     * the activity feed sit directly on the canvas); a grouped list paints the card.
     */
    cardBackground: Color = Color.Unspecified,
    onTogglePin: (() -> Unit)? = null,
    /**
     * This row's tag colours, already resolved from its `tagIds` by the list.
     *
     * Dots rather than named chips: a phone row has room for two or three 6dp dots
     * beside the folder/model line, and the names are available on the session screen.
     */
    tagColors: List<Color> = emptyList(),
    onDelete: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    // Relative time needs a clock, not `Instant.now()` inside composition (which is
    // non-idempotent and froze the label until something else forced a recomposition).
    val now by rememberNowInstant()
    // A fixed 26dp avatar next to text that grows with the system font scale ends up
    // looking like a stray dot at 200% text. Scaling it with the label keeps the row's
    // proportions intact, which is the whole point of letting the text reflow. Capped so
    // an extreme accessibility setting cannot push the avatar past the two text lines.
    val avatarSize = 26.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)

    Box {
        Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    when {
                        selected -> Modifier.background(colors.accent.copy(alpha = 0.18f))
                        cardBackground != Color.Unspecified -> Modifier.background(cardBackground)
                        else -> Modifier
                    },
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
                // A delegation child is indented one step, which is how the desktop
                // sidebar shows depth without opening a separate panel. 24dp per level
                // lines the child's avatar up under the parent's title.
                .padding(start = 8.dp + 24.dp * depth, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box {
                AgentAvatar(conversation.agentType, size = avatarSize)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(avatarSize * 0.31f)
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
                    // The context (folder, model) yields to the time. It lives in a
                    // weighted group so at a large system font scale — or with a long
                    // model name — it shrinks and ellipsizes while the timestamp keeps
                    // its intrinsic width. The other way round, the time could be
                    // squeezed out entirely, and it is the field that orders the list.
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
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
                        // Tag dots lead the metadata line: they are the user's own
                        // classification, and scanning by colour is how the desktop
                        // sidebar is read.
                        for (c in tagColors.take(3)) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(c),
                            )
                        }
                        // Deliberately NOT the model. `conversation.model` is the model the
                        // session was *first* run with — the server seeds that column once
                        // and never updates it on a switch (its own comment calls it "a chip
                        // nobody reads mid-turn"). Drawing it here read as "this session is
                        // using X" and was wrong for any session whose model changed. The
                        // desktop sidebar shows no model on a row either; the live value
                        // belongs to the session screen, which reads it from the ACP config.
                        if (tagColors.isNotEmpty() && (folderName != null || conversation.messageCount > 0)) {
                            MetaDot()
                        }
                        if (conversation.messageCount > 0) {
                            Text(
                                text = conversation.messageCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                                maxLines = 1,
                            )
                            MetaDot()
                        }
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

            // A row with delegation children carries a chevron, the way the desktop
            // sidebar expands a conversation into its sub-session subtree. It sits
            // before the status glyph so the expand target is always in the same place.
            if (conversation.childCount > 0 && onToggleChildren != null) {
                if (loadingChildren) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.textTertiary,
                    )
                } else {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (expanded) 0f else -90f)
                            .clickable(onClick = onToggleChildren),
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
        // Inset to the title, not the row edge — iOS starts a grouped list's separator
        // where the text starts so the leading avatar column stays visually open.
        if (showDivider) {
            HorizontalDivider(
                color = colors.hairline,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 45.dp, end = 8.dp),
            )
        }
        }

        if (onTogglePin != null || onDelete != null) {
            CodegMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onTogglePin != null) {
                    CodegMenuItem(
                        text = if (conversation.isPinned) {
                            stringResource(R.string.session_unpin)
                        } else {
                            stringResource(R.string.session_pin)
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
                    CodegMenuItem(
                        text = stringResource(R.string.common_delete),
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        danger = true,
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
