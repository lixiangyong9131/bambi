package com.shieldstream.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.onBackKey
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.theme.AppColors

/**
 * Standalone dialog for per-show intro/outro skip settings in Netflix TV modal style.
 * Kept OUTSIDE the DetailsActions side panel to avoid nested scroll constraints on TV.
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
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWithRetry() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f))
                .onBackKey(enabled = true, onBack = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .width(520.dp)
                    .clip(shape)
                    .background(Color(0xFA121214))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                    .padding(26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(20.dp)
                            .background(AppColors.Accent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        localizedStringResource(StringsR.string.details_skip_settings),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    localizedStringResource(StringsR.string.details_skip_hint),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp),
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Spacer(Modifier.height(12.dp))
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_enable),
                    icon = null,
                    detail = if (enabled) "ON" else "OFF",
                    highlighted = enabled,
                    onClick = { onConfig(!enabled, introSec, outroSec) },
                    modifier = Modifier.focusRequester(firstRequester),
                )
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_intro),
                    icon = null,
                    detail = if (introSec == 0) localizedStringResource(StringsR.string.details_skip_off)
                    else localizedStringResource(StringsR.string.details_skip_seconds, introSec),
                    highlighted = introSec > 0,
                    onClick = { onConfig(enabled, shift(introSec, 1), outroSec) },
                    onShift = { d -> onConfig(enabled, shift(introSec, d), outroSec) },
                )
                MoreRow(
                    text = localizedStringResource(StringsR.string.details_skip_outro),
                    icon = null,
                    detail = if (outroSec == 0) localizedStringResource(StringsR.string.details_skip_off)
                    else localizedStringResource(StringsR.string.details_skip_seconds, outroSec),
                    highlighted = outroSec > 0,
                    onClick = { onConfig(enabled, introSec, shift(outroSec, 1)) },
                    onShift = { d -> onConfig(enabled, introSec, shift(outroSec, d)) },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = localizedStringResource(StringsR.string.player_back_to_return_to_the_movie),
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
