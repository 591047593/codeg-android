package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState

/**
 * The composer's session settings, as one chip that reads out the current values.
 *
 * This is the desktop client's `SessionSettingsPicker`: a single control above the input
 * that shows every value at once ("Opus 5.5 / High / Plan") and opens the full editor.
 * The app kept the same settings behind an avatar in the top bar, so the model actually
 * in use was invisible unless you opened a sheet — the most direct reason the app
 * "showed the wrong model" compared to the desktop.
 *
 * Values are ordered the way the desktop ranks them: model first, then reasoning effort,
 * then mode, then anything else that is set. A setting with no current value is skipped
 * rather than shown as an empty slot.
 */
@Composable
fun SessionSettingsChip(
    summary: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    if (summary.isEmpty()) return

    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = summary.joinToString(" / "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The chip's text: each setting's current label, ranked model → effort → mode → other.
 *
 * Pure so it can be unit-tested without composition; returns an empty list when nothing
 * has a value (a fresh session whose catalog has not arrived), which is the signal to
 * hide the chip rather than draw an empty one.
 */
internal fun sessionSettingsSummary(
    modes: SessionModeState?,
    configOptions: List<SessionConfigOption>,
): List<String> {
    data class Entry(val rank: Int, val index: Int, val label: String)

    val entries = mutableListOf<Entry>()
    configOptions.forEachIndexed { index, option ->
        val current = option.kind.currentValue?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val label = option.kind.displayLabel(current)
        entries += Entry(rank = option.rank(), index = index, label = label)
    }
    // The legacy mode list is the fallback for agents that report modes instead of a
    // `mode` config option; it ranks with the other mode-shaped settings.
    modes?.let { m ->
        val current = m.availableModes.firstOrNull { it.id == m.currentModeId }
        val label = current?.name?.takeIf { it.isNotBlank() } ?: current?.id?.takeIf { it.isNotBlank() }
        if (label != null) entries += Entry(rank = 2, index = Int.MAX_VALUE, label = label)
    }

    return entries
        .sortedWith(compareBy({ it.rank }, { it.index }))
        .map { it.label }
}

/** Same ordering the desktop's `rankOf` uses. */
private fun SessionConfigOption.rank(): Int = when {
    // A boolean switch is a toggle, not a value worth reading out in the chip.
    kind.type == "boolean" -> 4
    id == MODEL_CONFIG_OPTION_ID || category == "model" -> 0
    category == "thought_level" -> 1
    category == "mode" -> 2
    else -> 3
}

/** The option's human label for [value], falling back to the raw value. */
private fun app.codeg.android.core.model.SessionConfigKind.displayLabel(value: String): String {
    options.firstOrNull { it.value == value }?.name?.takeIf { it.isNotBlank() }?.let { return it }
    for (group in groups) {
        group.options.firstOrNull { it.value == value }?.name?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return value
}

/** The id the server uses for the model selector; see the desktop's `model-config-groups`. */
internal const val MODEL_CONFIG_OPTION_ID = "model"
