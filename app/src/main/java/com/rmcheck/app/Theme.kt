package com.rmcheck.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same calm light palette as the desktop app
val BgColor = Color(0xFFF3F3F3)
val CardColor = Color(0xFFFFFFFF)
val BorderColor = Color(0xFFD6D6D6)
val BorderStrong = Color(0xFF8C8C8C)
val TextColor = Color(0xFF1B1B1B)
val SubText = Color(0xFF444444)
val MutedColor = Color(0xFF707070)
val PlaceholderColor = Color(0xFF8C8C8C)
val Accent = Color(0xFF0F6CBD)
val AccentSoft = Color(0xFFA9CDEE)
val TrackColor = Color(0xFFE4E4E4)
val GreenColor = Color(0xFF107C10)
val AmberColor = Color(0xFF9D5D00)
val RedColor = Color(0xFFC42B1C)
val DisabledBg = Color(0xFFE5E5E5)

data class ChipStyle(val bg: Color, val fg: Color)

val OkStyle = ChipStyle(Color(0xFFDFF6DD), Color(0xFF0E700E))
val WarnStyle = ChipStyle(Color(0xFFFFF4CE), Color(0xFF6E5700))
val BadStyle = ChipStyle(Color(0xFFFDE7E9), Color(0xFFA4262C))
val IdleStyle = ChipStyle(Color(0xFFE8E8E8), Color(0xFF616161))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = BgColor,
            onBackground = TextColor,
            surface = CardColor,
            onSurface = TextColor
        ),
        content = content
    )
}
