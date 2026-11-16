package com.Johnny.wcx.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.Johnny.wcx.ui.utils.theme.ModuleTheme

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val context = CommonContextWrapper(context)

    // Dialog 的 window 主题也必须跟随明暗：之前固定用 Theme_DeviceDefault_Light_Dialog，
    // 深色模式下输入框光标/选中背景/系统默认文字色都是浅色主题里的，跟圆角渐变外壳不搭。
    val darkDialog = com.Johnny.wcx.ui.utils.theme.ThemeSettings.themeMode.resolve(context)
    val dialog = Dialog(
        context,
        if (darkDialog) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar_MinWidth
        else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )
    val lifecycleOwner = XposedLifecycleOwner.create()

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

        setContentView(
            ComposeView(context).apply {
                setLifecycleOwner(lifecycleOwner)

                // Disable view state saving to prevent NotSerializableException.
                // Dialogs shown via showComposeDialog are ephemeral and don't need
                // state restoration. When the host Activity's onSaveInstanceState
                // fires, the ComposeView would try to serialize its SavedStateRegistry
                // which may contain non-serializable Compose internal objects.
                isSaveEnabled = false

                setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        ModuleTheme {
                            Box(
                                modifier = Modifier.wrapContentSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                scope.content()
                            }
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setOnDismissListener { lifecycleOwner.onDestroy() }
        show()
    }
}

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}
