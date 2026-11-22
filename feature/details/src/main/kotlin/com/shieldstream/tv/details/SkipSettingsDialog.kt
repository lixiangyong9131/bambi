package com.shieldstream.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.theme.AppColors

/**
 * Modified build: standalone dialog for per-show intro/outro skip settings.
 * Kept OUTSIDE the DetailsActions side panel - nesting another scroll container there
 * caused "infinity maximum height constraints" crashes on the TV build.
 */
@Composable
internal fun SkipSettingsDialog(
    enabled: Boolean,
    introSec: Int,
    outroSec: Int,
    onConfig: (Boolean, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val steps = listOf(0, 30, 60, 90, 120, 150, 180)
    fun shift(current: Int, delta: Int): Int {
        val idx = steps.indexOf(current).let { if (it < 0) 0 else it }
        return steps[(idx + delta).coerceIn(0, steps.lastIndex)]
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .onBackKey(enabled = true, onBack = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(560.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppColors.BackgroundRaised)
                    .padding(28.dp),
            ) {
                Text(
                    localizedStringResource(StringsR.string.details_skip_settings),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    localizedStringResource(StringsR.string.details_skip_hint),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(24.dp))
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_enable),
                    icon = null,
                    detail = if (enabled) "ON" else "OFF",
                    highlighted = enabled,
                    onClick = { onConfig(!enabled, introSec, outroSec) },
                    modifier = Modifier,
                )
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_intro),
                    icon = null,
                    detail = if (introSec == 0) localizedStringResource(StringsR.string.details_skip_off)
                            else localizedStringResource(StringsR.string.details_skip_seconds, introSec),
                    highlighted = introSec > 0,
                    onClick = { onConfig(enabled, shift(introSec, 1), outroSec) },
                    onShift = { d -> onConfig(enabled, shift(introSec, d), outroSec) },
                    modifier = Modifier,
                )
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_outro),
                    icon = null,
                    detail = if (outroSec == 0) localizedStringResource(StringsR.string.details_skip_off)
                            else localizedStringResource(StringsR.string.details_skip_seconds, outroSec),
                    highlighted = outroSec > 0,
                    onClick = { onConfig(enabled, introSec, shift(outroSec, 1)) },
                    onShift = { d -> onConfig(enabled, introSec, shift(outroSec, d)) },
                    modifier = Modifier,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = localizedStringResource(StringsR.string.player_back_to_return_to_the_movie),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
