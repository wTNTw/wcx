package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.Johnny.wcx.ui.style.WxPalette

@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    // 全局弹窗外壳：大圆角 + 极浅渐变，所有功能弹窗共用一套观感。
    // 渐变必须跟主题走：之前硬编码 Color.White → #F4FAF7，深色模式下整个弹窗是白底
    // （字是浅色，直接看不见）。深色时改用 M3 的 surfaceContainer 层色，
    // 这样也能跟随「自定义取色 / 动态壁纸取色」。
    val scheme = MaterialTheme.colorScheme
    val dialogBackground = if (scheme.surface.luminance() < 0.5f) {
        Brush.verticalGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainer))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFF4FAF7)))
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier
                .background(dialogBackground)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                title?.let {
                    val customStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                    CompositionLocalProvider(
                        LocalTextStyle provides customStyle,
                        LocalContentColor provides WxPalette.Primary,
                    ) {
                        it()
                    }
                }
            }

            HorizontalDivider()

            text?.let {
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val bodyColor = MaterialTheme.colorScheme.onSurface

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    CompositionLocalProvider(
                        LocalTextStyle provides bodyStyle,
                        LocalContentColor provides bodyColor
                    ) {
                        it()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val buttonTextStyle = MaterialTheme.typography.labelLarge
                CompositionLocalProvider(LocalTextStyle provides buttonTextStyle) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}
