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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // 全局弹窗外壳：大圆角，底色与设置页「一级选项卡片」完全一致
    // （BaseItemContainer 用的是 colorScheme.surfaceBright）。
    // 注意两点：
    // 1) 弹窗内的选项用的 M3 ListItem 默认容器色是 colorScheme.surface，
    //    只改弹窗底色会让选项块比背景更深/更黑，所以这里把局部主题的 surface
    //    也指向同一个颜色，两者才会真正「同色」。
    // 2) 深浅色两种模式都用这个颜色：一级选项卡片本身就是靠 surfaceBright 与
    //    页面底色拉开层次，弹窗跟着它走就不会出现纯黑底。
    val scheme = MaterialTheme.colorScheme
    val containerColor = scheme.surfaceBright
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        MaterialTheme(colorScheme = scheme.copy(surface = containerColor)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
}
