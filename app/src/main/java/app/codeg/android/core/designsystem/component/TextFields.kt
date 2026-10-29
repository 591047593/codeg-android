package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * A labelled text field, filled rather than outlined.
 *
 * iOS does not outline its text fields: a rounded grey well is the whole affordance, and
 * a label sits above it rather than floating inside the box. `OutlinedTextField` put a
 * 1dp stroke around every input and a floating label that jumped on focus, which is the
 * Material idiom and the first thing that reads as "Android" on a form screen.
 *
 * All fields in the app go through this file, so the shape stays consistent.
 */
@Composable
fun CodegTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    singleLine: Boolean = true,
) {
    val colors = CodegTheme.colors
    // The label doubles as the placeholder when none was given. Without this, a field
    // whose caller omitted `placeholder` rendered as an empty grey well with nothing to
    // say what belongs in it (the server-name field did exactly that).
    val hint = placeholder ?: label
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint, color = colors.textTertiary) },
        singleLine = singleLine,
        textStyle = if (mono) {
            TextStyle(fontFamily = CodegMono, color = colors.textPrimary)
        } else {
            TextStyle(color = colors.textPrimary)
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        // A single-line field is 54dp. 48dp clipped the placeholder: M3 reserves ~16dp of
        // vertical padding inside a `TextField`, so the box has to be taller than the
        // iOS control height to fit the same text. `height` rather than `heightIn`
        // because the component's own 56dp floor would otherwise win.
        modifier = modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier.height(54.dp) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = codegFieldColors(),
    )
}

/** Token entry: masked by default with a show/hide toggle, monospaced (iOS `SecretField`). */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    val colors = CodegTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(label, color = colors.textTertiary) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = CodegMono, color = colors.textPrimary),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = label,
                    tint = colors.textTertiary,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = codegFieldColors(),
    )
}

/**
 * Shared field colours: a grey well that darkens slightly on focus, no indicator line.
 *
 * `secondarySystemBackground` is the iOS fill for a field sitting on a grouped
 * background; the focus state only lifts its tone rather than drawing a 2dp accent line
 * under the text, which is Material's tell.
 */
@Composable
private fun codegFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = CodegTheme.colors.codeSurface,
    unfocusedContainerColor = CodegTheme.colors.codeSurface,
    // Fields that cannot be edited should look inert rather than merely dimmer.
    disabledContainerColor = CodegTheme.colors.codeSurface.copy(alpha = 0.5f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = CodegTheme.colors.accent,
    focusedTextColor = CodegTheme.colors.textPrimary,
    unfocusedTextColor = CodegTheme.colors.textPrimary,
)
