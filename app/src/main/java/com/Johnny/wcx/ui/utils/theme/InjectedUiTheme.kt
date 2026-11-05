package com.Johnny.wcx.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 简化版注入主题：用模块自身 MaterialTheme 包裹注入到微信内的 Compose 内容。
 * （WeKit 版本依赖其多语言/i18n 主题体系，本模块不连带，使用标准 MaterialTheme。）
 *
 * 注意：这个函数以前是「传了 darkTheme 也不看」的空壳（只写 `MaterialTheme { }`），
 * 于是侧边栏等 方式二 的界面永远亮色。现在按 [darkTheme]（为空则跟随系统）取模块自带的
 * [darkScheme] / [lightScheme]；需要精确控制时可用 [colorSchemeOverride] 直接指定配色。
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val scheme = colorSchemeOverride ?: if (dark) darkScheme else lightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
