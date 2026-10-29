package app.codeg.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.CodegLogo
import app.codeg.android.core.designsystem.theme.CodegTheme

/** Shown when no server is configured (iOS `OnboardingView`). */
@Composable
fun OnboardingScreen(
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Top-aligned with breathing room rather than vertically centred. Centering left
        // the whole page floating in the middle of the screen with dead space above and
        // below — it reads as an empty screen rather than as a welcome, and any keyboard
        // or font-scale change pushes the content around instead of extending it.
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
    ) {
        // Pushes the hero block down enough to feel intentional without centring the lot.
        Spacer(Modifier.height(48.dp))
        // The product mark rather than a generic code glyph: this is the first screen a
        // new user sees, and the `</>` with its three coloured dots is what identifies
        // the app.
        CodegLogo(size = 64.dp)
        Text(
            stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
        )

        Column(
            modifier = Modifier.padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FeatureRow(Icons.Rounded.Visibility, stringResource(R.string.onboarding_feature_watch))
            FeatureRow(Icons.Rounded.Bolt, stringResource(R.string.onboarding_feature_steer))
            FeatureRow(Icons.Rounded.PlayArrow, stringResource(R.string.onboarding_feature_start))
        }

        PrimaryButton(
            text = stringResource(R.string.onboarding_add_server),
            onClick = onAddServer,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Text(
            stringResource(R.string.onboarding_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = CodegTheme.colors.accent, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = CodegTheme.colors.textPrimary)
    }
}
