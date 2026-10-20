package app.purse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.purse.data.DarkMode
import app.purse.data.Settings

/** A flat palette; every theme family supplies one for dark and (optionally) one for light. */
data class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val fg: Color,
    val muted: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
)

data class ThemeFamily(val id: String, val name: String, val dark: Palette, val light: Palette?)

private fun c(hex: Long) = Color(0xFF000000 or hex)

object Palettes {
    val all = listOf(
        ThemeFamily(
            "gruvbox", "Gruvbox",
            dark = Palette(c(0x282828), c(0x32302f), c(0x504945), c(0xebdbb2), c(0xa89984), c(0xfabd2f), c(0x83a598), c(0x8ec07c), c(0xfb4934), c(0xb8bb26), c(0xfe8019)),
            light = Palette(c(0xfbf1c7), c(0xf2e5bc), c(0xd5c4a1), c(0x3c3836), c(0x7c6f64), c(0xb57614), c(0x076678), c(0x427b58), c(0x9d0006), c(0x79740e), c(0xaf3a03)),
        ),
        ThemeFamily(
            "nord", "Nord",
            dark = Palette(c(0x2e3440), c(0x3b4252), c(0x4c566a), c(0xeceff4), c(0xa3adbf), c(0x88c0d0), c(0x81a1c1), c(0xb48ead), c(0xbf616a), c(0xa3be8c), c(0xebcb8b)),
            light = Palette(c(0xeceff4), c(0xe5e9f0), c(0xd8dee9), c(0x2e3440), c(0x4c566a), c(0x5e81ac), c(0x81a1c1), c(0xb48ead), c(0xbf616a), c(0x6f8f55), c(0xc08b2c)),
        ),
        ThemeFamily(
            "everforest", "Everforest",
            dark = Palette(c(0x2d353b), c(0x343f44), c(0x475258), c(0xd3c6aa), c(0x859289), c(0xa7c080), c(0x7fbbb3), c(0xd699b6), c(0xe67e80), c(0xa7c080), c(0xdbbc7f)),
            light = Palette(c(0xfdf6e3), c(0xf4f0d9), c(0xe6e2cc), c(0x5c6a72), c(0x939f91), c(0x8da101), c(0x3a94c5), c(0xdf69ba), c(0xf85552), c(0x8da101), c(0xdfa000)),
        ),
        ThemeFamily(
            "catppuccin", "Catppuccin",
            dark = Palette(c(0x1e1e2e), c(0x313244), c(0x45475a), c(0xcdd6f4), c(0xa6adc8), c(0xcba6f7), c(0x89b4fa), c(0x94e2d5), c(0xf38ba8), c(0xa6e3a1), c(0xf9e2af)),
            light = Palette(c(0xeff1f5), c(0xe6e9ef), c(0xccd0da), c(0x4c4f69), c(0x6c6f85), c(0x8839ef), c(0x1e66f5), c(0x179299), c(0xd20f39), c(0x40a02b), c(0xdf8e1d)),
        ),
        ThemeFamily(
            "tokyonight", "Tokyo Night",
            dark = Palette(c(0x1a1b26), c(0x24283b), c(0x414868), c(0xc0caf5), c(0x9aa5ce), c(0x7aa2f7), c(0xbb9af7), c(0x7dcfff), c(0xf7768e), c(0x9ece6a), c(0xe0af68)),
            light = Palette(c(0xe1e2e7), c(0xd5d6db), c(0xc4c8da), c(0x3760bf), c(0x6172b0), c(0x2e7de9), c(0x9854f1), c(0x007197), c(0xf52a65), c(0x587539), c(0x8c6c3e)),
        ),
        ThemeFamily(
            "rosepine", "Rosé Pine",
            dark = Palette(c(0x191724), c(0x1f1d2e), c(0x26233a), c(0xe0def4), c(0x908caa), c(0xebbcba), c(0xc4a7e7), c(0x9ccfd8), c(0xeb6f92), c(0x9ccfd8), c(0xf6c177)),
            light = Palette(c(0xfaf4ed), c(0xfffaf3), c(0xf2e9e1), c(0x575279), c(0x797593), c(0xd7827e), c(0x907aa9), c(0x56949f), c(0xb4637a), c(0x286983), c(0xea9d34)),
        ),
        ThemeFamily(
            "solarized", "Solarized",
            dark = Palette(c(0x002b36), c(0x073642), c(0x0e4552), c(0x93a1a1), c(0x657b83), c(0x268bd2), c(0x2aa198), c(0x6c71c4), c(0xdc322f), c(0x859900), c(0xb58900)),
            light = Palette(c(0xfdf6e3), c(0xeee8d5), c(0xe4ddc8), c(0x586e75), c(0x93a1a1), c(0x268bd2), c(0x2aa198), c(0x6c71c4), c(0xdc322f), c(0x859900), c(0xb58900)),
        ),
        ThemeFamily(
            "dracula", "Dracula",
            dark = Palette(c(0x282a36), c(0x343746), c(0x44475a), c(0xf8f8f2), c(0x8b95c9), c(0xbd93f9), c(0xff79c6), c(0x8be9fd), c(0xff5555), c(0x50fa7b), c(0xf1fa8c)),
            light = null,
        ),
    )

    /** Material You colours from the wallpaper (Android 12+); falls back to Gruvbox. */
    const val DYNAMIC = "dynamic"

    fun family(id: String): ThemeFamily = all.firstOrNull { it.id == id } ?: all.first()

    fun resolve(id: String, dark: Boolean): Palette {
        val f = family(id)
        return if (dark) f.dark else f.light ?: f.dark
    }
}

/** Colours with a fixed meaning across the app. */
data class PurseColors(val expense: Color, val income: Color, val transfer: Color, val warning: Color, val isDark: Boolean)

val LocalPurseColors = staticCompositionLocalOf {
    PurseColors(Color.Red, Color.Green, Color.Blue, Color.Yellow, true)
}

private fun Palette.toScheme(dark: Boolean): ColorScheme {
    val onAccent = if (dark) bg else Color.White
    val container = { accent: Color -> lerp(bg, accent, if (dark) 0.28f else 0.22f) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onAccent,
        primaryContainer = container(primary), onPrimaryContainer = fg,
        secondary = secondary, onSecondary = onAccent,
        secondaryContainer = container(secondary), onSecondaryContainer = fg,
        tertiary = tertiary, onTertiary = onAccent,
        tertiaryContainer = container(tertiary), onTertiaryContainer = fg,
        background = bg, onBackground = fg,
        surface = bg, onSurface = fg,
        surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
        surfaceTint = primary,
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = primary,
        error = red, onError = onAccent,
        errorContainer = container(red), onErrorContainer = fg,
        outline = muted, outlineVariant = surfaceHigh,
        surfaceBright = surfaceHigh, surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = lerp(bg, surface, 0.5f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(surface, surfaceHigh, 0.5f),
        surfaceContainerHighest = surfaceHigh,
    )
}

@Composable
fun PurseTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val context = LocalContext.current
    val palette = Palettes.resolve(settings.theme, dark)
    val scheme = if (settings.theme == Palettes.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        palette.toScheme(dark)
    }
    val colors = PurseColors(
        expense = palette.red,
        income = palette.green,
        transfer = palette.secondary,
        warning = palette.yellow,
        isDark = dark,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalPurseColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
