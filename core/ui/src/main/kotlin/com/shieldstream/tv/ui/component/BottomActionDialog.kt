package com.shieldstream.tv.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.LocalLightweightUi

@Composable
fun BottomActionDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.84f,
    bottomPadding: Dp = 44.dp,
    consumeOpeningKeyRelease: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = scrimAlpha * 0.78f),
                    0.55f to Color.Black.copy(alpha = scrimAlpha),
                    1f to Color.Black.copy(alpha = (scrimAlpha + 0.14f).coerceAtMost(1f)),
                ),
            )
            .then(if (consumeOpeningKeyRelease) Modifier.consumeInitialConfirmationKeyUp() else Modifier)
            .onBackKey(enabled = true, onBack = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.padding(bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 640.dp),
            )
            content()
        }
    }
}

data class ActionMenuItem(
    val text: String,
    val icon: ImageVector?,
    val onClick: () -> Unit,
)

/**
 * Netflix TV style centered modal action menu with full-width inverted-white focus rows.
 * [consumeOpeningKeyRelease] prevents the long-press OK key-up from accidentally firing the first item.
 */
@Composable
fun ActionMenuDialog(
    title: String,
    actions: List<ActionMenuItem>,
    onDismiss: () -> Unit,
    scrimAlpha: Float = 0.84f,
    consumeOpeningKeyRelease: Boolean = false,
) {
    val firstActionRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        firstActionRequester.requestFocusWithRetry()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha.coerceIn(0.75f, 0.94f)))
            .then(if (consumeOpeningKeyRelease) Modifier.consumeInitialConfirmationKeyUp() else Modifier)
            .onBackKey(enabled = true, onBack = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(MenuPanelShape)
                .background(Color(0xFA121214))
                .border(1.dp, Color.White.copy(alpha = 0.12f), MenuPanelShape)
                .padding(horizontal = 24.dp, vertical = 22.dp),
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
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    NetflixActionMenuRow(
                        text = action.text,
                        icon = action.icon,
                        onClick = action.onClick,
                        modifier = if (index == 0) Modifier.focusRequester(firstActionRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetflixActionMenuRow(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val contentColor = if (focused) Color.Black else Color.White.copy(alpha = 0.90f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(ActionRowShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.05f))
            .tvClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Legacy helper kept for compatibility; styled cleanly in Netflix monochrome palette.
 */
@Composable
fun ActionDockCard(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (LocalLightweightUi.current) {
        if (focused) 1.05f else 1f
    } else {
        animateFloatAsState(if (focused) 1.05f else 1f, tween(110), label = "actionCardScale").value
    }
    val content = if (focused) Color.Black else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .width(156.dp)
            .height(96.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ActionRowShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.14f), ActionRowShape)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = text,
            color = content,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val MenuPanelShape = RoundedCornerShape(10.dp)
private val ActionRowShape = RoundedCornerShape(6.dp)
