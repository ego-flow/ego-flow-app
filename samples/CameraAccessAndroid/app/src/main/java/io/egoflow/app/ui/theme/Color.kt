package io.egoflow.app.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette -- converted from ego-flow-server/frontend/src/styles.css OKLCH values
object LightPalette {
    val Background = Color(0xFFF1FBFB)
    val Foreground = Color(0xFF19262B)
    val Card = Color(0xFFFFFFFF)
    val Primary = Color(0xFF007BAF)
    val PrimaryForeground = Color(0xFFF6F9FB)
    val Secondary = Color(0xFFE4EDEF)
    val SecondaryForeground = Color(0xFF28353A)
    val Muted = Color(0xFFE7F0F2)
    val MutedForeground = Color(0xFF606F74)
    val Accent = Color(0xFFC7EDED)
    val AccentForeground = Color(0xFF1B3237)
    val Destructive = Color(0xFFDF2321)
    val Border = Color(0xFFD4DDDF)
    val Ring = Color(0xFF0094BD)
}

// Dark palette
object DarkPalette {
    val Background = Color(0xFF0C181D)
    val Foreground = Color(0xFFEFF2F5)
    val Card = Color(0xFF182429)
    val Primary = Color(0xFF4AB2D9)
    val PrimaryForeground = Color(0xFF0D181E)
    val Secondary = Color(0xFF2D3A3F)
    val SecondaryForeground = Color(0xFFEFF2F5)
    val Muted = Color(0xFF243036)
    val MutedForeground = Color(0xFFA5B4BB)
    val Accent = Color(0xFF7FD4E8)
    val AccentForeground = Color(0xFF0D181E)
    val Destructive = Color(0xFFF05656)
    val Border = Color(0x40FFFFFF) // white at 25%
    val Ring = Color(0xFF3BB3DE)
}

// Brand-specific tokens not in Material3 ColorScheme
object BrandColors {
    // Sea-ink (primary text in web)
    val SeaInkLight = Color(0xFF18343B)
    val SeaInkDark = Color(0xFFE8F4F7)

    // Sea-ink-soft (secondary text in web)
    val SeaInkSoftLight = Color(0xFF4E6970)
    val SeaInkSoftDark = Color(0xFFB6CBD1)

    // Lagoon-deep (accent/interactive in web)
    val LagoonDeepLight = Color(0xFF2F8C9A)
    val LagoonDeepDark = Color(0xFF57B7C7)

    // Status colors (carried from original AppColor)
    val StatusGreen = Color(0xFF61BC63)
    val StatusRed = Color(0xFFFF3B30)
    val StatusYellow = Color(0xFFFFCC00)
}
