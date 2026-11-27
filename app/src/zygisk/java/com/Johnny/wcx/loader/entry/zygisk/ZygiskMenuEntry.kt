package com.Johnny.wcx.loader.entry.zygisk

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.home_screen_menu.ModuleSettings
import com.Johnny.wcx.ui.utils.ExtensionIcon
import com.tencent.mm.ui.LauncherUI
import de.robv.android.xposed.XC_MethodHook

/**
 * Always-available "WCX" entry in the home screen "+" menu.
 *
 * The regular [ModuleSettings] entry is a [com.Johnny.wcx.features.core.SwitchFeature]
 * that defaults to off, and it is normally enabled from the module's own app
 * icon. A Zygisk payload has no launcher icon, so without this entry there
 * would be no way to reach the settings at all.
 *
 * This class lives in `app/src/zygisk`, so it only exists in the payload build.
 * It yields to [ModuleSettings] when that entry is active, keeping a single
 * menu item.
 */
@Feature(
    name = "Zygisk 模块设置入口",
    categories = ["Zygisk"],
    description = "在首页右上角菜单常驻「WCX」入口（Zygisk 部署没有应用图标）",
)
object ZygiskMenuEntry : ApiFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    private const val TAG = "ZygiskMenuEntry"

    override fun onEnable() {
        com.Johnny.wcx.utils.WeLogger.i(TAG, "registering Zygisk settings entry")
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        com.Johnny.wcx.utils.WeLogger.i(
            TAG,
            "getMenuItems requested; regular entry active=${ModuleSettings.isActive}",
        )
        // The user may still enable the regular entry from settings; never show two.
        if (ModuleSettings.isActive) return emptyList()
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0,
                BuildConfig.TAG,
                ExtensionIcon,
            ) {
                LauncherUI.getInstance()?.let { WeSettingsInjector.openSettingsDialog(it) }
            },
        )
    }
}
