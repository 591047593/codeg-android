package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.theme.CodegTheme

/** A labelled single-line text field themed to the app palette. */
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace) else TextStyle.Default,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        colors = fieldColors(),
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    tint = CodegTheme.colors.textTertiary,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CodegTheme.colors.accent,
    unfocusedBorderColor = CodegTheme.colors.surfaceStroke,
    focusedLabelColor = CodegTheme.colors.accent,
    unfocusedLabelColor = CodegTheme.colors.textTertiary,
    cursorColor = CodegTheme.colors.accent,
    focusedTextColor = CodegTheme.colors.textPrimary,
    unfocusedTextColor = CodegTheme.colors.textPrimary,
)

/**
 * 批v160 W10: the form input card — the multi-line editor for dispatch/idea
 * sheets. A filled+outlined hybrid: radiusMd (14dp) corners, an elevated fill
 * that lifts the field off the sheet, a hairline stroke that warms to the accent
 * on focus, and generous inner padding so long prompts don't feel cramped.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CodegFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 8,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusMd)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        trailingIcon = trailingIcon,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = colors.textPrimary,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
        ),
        shape = shape,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent.copy(alpha = 0.75f),
            unfocusedBorderColor = colors.surfaceStroke,
            focusedContainerColor = colors.bg.copy(alpha = 0.42f),
            unfocusedContainerColor = colors.bg.copy(alpha = 0.42f),
            cursorColor = colors.accent,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textTertiary,
        ),
    )
}

/** 批v160 W10: the small section header above a field group — SemiBold, slightly
 *  tracked, tertiary. The tracking is what keeps a stack of these legible. */
@Composable
fun CodegSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = CodegTheme.colors.textTertiary,
        modifier = modifier,
    )
}
