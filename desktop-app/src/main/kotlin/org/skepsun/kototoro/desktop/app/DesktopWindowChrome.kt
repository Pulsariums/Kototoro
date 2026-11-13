package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopPlatform
import java.awt.Window

@Composable
internal fun DesktopWindowChrome(window: ComposeWindow, appearance: DesktopAppearance) {
    val dark = appearance.isDark()
    val colors = desktopColorScheme(dark)
    LaunchedEffect(window, dark, colors.background, colors.onBackground) {
        applyDesktopWindowChrome(window, dark, colors.background, colors.onBackground)
    }
}

private fun applyDesktopWindowChrome(window: Window, dark: Boolean, background: Color, onBackground: Color) {
    try {
        val awtBackground = java.awt.Color(background.toArgb(), true)
        window.background = awtBackground
        (window as? ComposeWindow)?.contentPane?.background = awtBackground
        if (DesktopPlatform.current != DesktopPlatform.WINDOWS) return
        val handle = Native.getWindowPointer(window) ?: return
        Dwmapi.INSTANCE.attribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
        Dwmapi.INSTANCE.attribute(handle, DWMWA_BORDER_COLOR, background.colorRef())
        Dwmapi.INSTANCE.attribute(handle, DWMWA_CAPTION_COLOR, background.colorRef())
        Dwmapi.INSTANCE.attribute(handle, DWMWA_TEXT_COLOR, onBackground.colorRef())
    } catch (_: Throwable) {
    }
}

private fun Color.colorRef(): Int {
    val argb = toArgb()
    return (argb and 0xFF00FF00.toInt()) or ((argb and 0xFF) shl 16) or ((argb shr 16) and 0xFF)
}

private fun Dwmapi.attribute(handle: Pointer, attribute: Int, value: Int) {
    val memory = Memory(4).apply { setInt(0, value) }
    DwmSetWindowAttribute(handle, attribute, memory, 4)
}

@Suppress("FunctionName")
private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int

    companion object {
        val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
