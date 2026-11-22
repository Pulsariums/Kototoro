package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopPlatform
import java.awt.Color as AwtColor

@Composable
internal fun DesktopWindowChrome(window: ComposeWindow, appearance: DesktopAppearance) {
    val dark = appearance.isDark()
    val scheme = desktopColorScheme(dark)
    LaunchedEffect(window, dark, scheme.background, scheme.onBackground) {
        applyDesktopWindowChrome(window, dark, scheme.background, scheme.onBackground)
    }
}

private fun applyDesktopWindowChrome(window: ComposeWindow, dark: Boolean, background: Color, onBackground: Color) {
    try {
        val awtBackground = AwtColor(background.toArgb(), true)
        window.background = awtBackground
        window.contentPane.background = awtBackground
        if (DesktopPlatform.current != DesktopPlatform.WINDOWS) return
        val handle = Native.getWindowPointer(window) ?: return
        Dwmapi.INSTANCE.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE,
            IntByReference(if (dark) 1 else 0), 4)
        Dwmapi.INSTANCE.DwmSetWindowAttribute(handle, DWMWA_BORDER_COLOR, IntByReference(background.colorRef()), 4)
        Dwmapi.INSTANCE.DwmSetWindowAttribute(handle, DWMWA_CAPTION_COLOR, IntByReference(background.colorRef()), 4)
        Dwmapi.INSTANCE.DwmSetWindowAttribute(handle, DWMWA_TEXT_COLOR, IntByReference(onBackground.colorRef()), 4)
    } catch (_: Throwable) {
    }
}

/** Win32's COLORREF is 0x00BBGGRR, not the ARGB int Compose packs. */
private fun Color.colorRef(): Int {
    val argb = toArgb()
    val red = argb shr 16 and 0xFF
    val green = argb shr 8 and 0xFF
    val blue = argb and 0xFF
    return blue shl 16 or (green shl 8) or red
}

@Suppress("FunctionName")
private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
