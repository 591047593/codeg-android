package app.codeg.android.feature.sessions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CountBadge
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * A grouped-list section header shared by the Chats and Activity tabs: a small tinted
 * glyph + an uppercase group label at `labelSmall` + a trailing [CountBadge], tappable
 * to collapse/expand its rows with a chevron that flips ▾↔▴.
 *
 * Deliberately *quieter* than the rows it groups. The previous version set the label at
 * `titleLarge` (18sp semibold) with a 26dp filled badge — the same diameter as an agent
 * avatar — so a group looked like a bigger, bolder row and the list had no readable
 * hierarchy: on the Chats tab, "qq-farm-bot-main" (a folder) and "当前项目专业度审查" (a
 * session) were nearly indistinguishable. A heading should read as a label above the
 * content, never as a peer of it.
 *
 * The whole row is the toggle target (the native accordion idiom). When [collapsible]
 * is false (an empty group), it renders statically — no chevron, no tap.
 */
@Composable
fun CollapsibleSectionHeader(
    icon: ImageVector,
    tint: Color,
    label: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    val colors = CodegTheme.colors
    // An empty group is kept on purpose (it mirrors iOS, so a folder you opened stays
    // visible before it has sessions), but it must not compete for attention: on a
    // device with a dozen empty folders the headers alone filled the screen and pushed
    // every real conversation below the fold. Rendering the whole header at reduced
    // opacity is enough to convey "exists, nothing in it" while the eye skips past.
    val alpha = if (count == 0) 0.55f else 1f
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        label = "section-chevron",
    )
    val toggleLabel = stringResource(
        if (collapsed) R.string.sessions_expand else R.string.sessions_collapse,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp) // inter-section gap, kept outside the ripple
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (collapsible) {
                    Modifier.clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A section header is a *label*, not a row: it must read as a quieter layer than
        // the conversations beneath it. It previously carried a 26dp filled badge — the
        // same diameter as an agent avatar — at `titleLarge` (18sp semibold), which made
        // every group look like another tappable session and left the list with no
        // readable hierarchy at all. Now it is a bare tinted glyph plus an uppercase
        // label at `labelSmall`: clearly a heading, clearly not a destination. The glyph
        // has no chip behind it because at this size a 1dp ring around an 8dp icon just
        // reads as noise.
        Icon(
            icon,
            contentDescription = null,
            tint = tint.copy(alpha = tint.alpha * alpha),
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary.copy(alpha = colors.textSecondary.alpha * alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            letterSpacing = 0.6.sp,
        )
        // A "0" badge is pure noise; the dimmed header already says the group is empty.
        if (count > 0) CountBadge(count)
        if (collapsible) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
    }
}
