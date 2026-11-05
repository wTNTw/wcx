package com.Johnny.wcx.ui.utils.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 简化版注入主题：用模块自身主题包裹注入到微信内的 Compose 内容。
 *
 * 历史坑：
 *  1. 曾经是「传了 darkTheme 也不看」的空壳（只写 `MaterialTheme { }`）→ 侧边栏永远亮色；
 *  2. 只改成取 dark/lightScheme → 又变成固定蓝调，和模块主题（微信绿 / 自定义主题色 /
 *     动态壁纸取色）不一致。
 *
 * 现在直接复用 [com.Johnny.wcx.ui.utils.InjectedUiTheme]（模块 UI 与其它注入界面用的那套）：
 * 配色由 SeedResolver 从 ThemeSettings 推导，深色跟随 [darkTheme]（为空则跟随系统）。
 * 需要临时覆盖配色时用 [colorSchemeOverride]。
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    if (colorSchemeOverride == null) {
        com.Johnny.wcx.ui.utils.InjectedUiTheme(darkTheme = darkTheme, content = content)
    } else {
        MaterialTheme(colorScheme = colorSchemeOverride, content = content)
    }
}
