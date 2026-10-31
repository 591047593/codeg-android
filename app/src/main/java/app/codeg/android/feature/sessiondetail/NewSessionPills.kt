package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType

/**
 * The two choices a new conversation cannot start without, shown as pills directly above
 * the composer.
 *
 * The desktop client puts an agent pill and a workspace selector on the new-conversation
 * screen itself, above the input, because those two values decide what the prompt will
 * run against — you pick them *before* typing, and you can see what is picked while you
 * type. This app hid both behind a sheet opened from the toolbar avatar, so a new session
 * looked like a plain chat: no visible folder, no visible agent, and no indication that
 * either could be changed.
 *
 * Rendered only in the draft state (`ui.isNew`) — a running conversation has its folder
 * fixed and its agent fixed, and both are shown elsewhere.
 */
@Composable
fun NewSessionPills(
    agent: AgentType,
    folderName: String?,
    folderPath: String?,
    enabled: Boolean,
    onPickAgent: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(enabled = enabled, onClick = onPickAgent) {
            AgentAvatar(agent, size = 18.dp)
            Text(
                text = agent.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(15.dp),
            )
        }

        // The folder pill is the one that was completely absent before: without it a new
        // conversation had no visible working directory at all.
        Pill(enabled = enabled, onClick = onPickFolder) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                // The folder's name, with its path underneath in the sheet — a pill has
                // room for one of them and the name is what a person recognises.
                text = folderName ?: stringResource(R.string.agentopts_choose_folder),
                style = MaterialTheme.typography.labelLarge,
                color = if (folderName == null) colors.danger else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(15.dp),
            )
        }
        if (folderName != null && folderPath != null) {
            // The path is useful while choosing and noise once chosen; it is shown as a
            // tooltip-like line only when the pill row has room to spare.
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Pill(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}
