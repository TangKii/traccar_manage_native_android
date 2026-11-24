package com.traccar.nativeapp.ui.compose.theme

import androidx.compose.ui.graphics.Color

// ── Neutral greys ───────────────────────────────────────────
// The quiet base: backgrounds, cards and text stay in neutral grey and out of the way.
// 移植自 Jiumi（Apache-2.0）。
val Neutral0 = Color(0xFFFFFFFF)
val Neutral50 = Color(0xFFFAFAFA)
val Neutral75 = Color(0xFFF5F5F5)
val Neutral90 = Color(0xFFF2F2F2)
val Neutral100 = Color(0xFFF0F0F0)
val Neutral150 = Color(0xFFE6E6E6)
val Neutral200 = Color(0xFFDADADA)
val Neutral300 = Color(0xFFD4D4D4)
val Neutral400 = Color(0xFFA8A8A8)
val Neutral500 = Color(0xFF686868)
val Neutral600 = Color(0xFF525252)
val Neutral700 = Color(0xFF363636)
val Neutral750 = Color(0xFF303030)
val Neutral800 = Color(0xFF272727)
val Neutral850 = Color(0xFF1B1B1B)
val Neutral900 = Color(0xFF202020)
val Neutral950 = Color(0xFF101010)

// ── Function accents ──
val AccentBlue = Color(0xFF0A84FF)
val AccentBlueVariant = Color(0xFF007AFF)
val AccentBlueContainerLight = Color(0xFFDCEEFF)
val AccentBlueContainerDark = Color(0xFF163350)
// Status colors
val StatusSuccess = Color(0xFF2FA36B)
val StatusWarning = Color(0xFFE5900F)
val StatusError = Color(0xFFE5484D)
val StatusErrorContainerDark = Color(0xFF321C1B)
val StatusOnErrorContainerDark = Color(0xFFFFDAD6)

// ── Ledger semantic entry points ──
val JiumiPrimary = AccentBlue
val JiumiBackground = Neutral950
val JiumiSurface = Neutral850
val JiumiSurfaceVariant = Neutral800
val JiumiOnBackground = Color(0xFFEDEDED)
val JiumiOnSurface = Color(0xFFEDEDED)

val JiumiSuccess = StatusSuccess
val JiumiWarning = StatusWarning
val JiumiError = StatusError
val JiumiInfo = AccentBlue

// Light theme
val LightCanvas = Color(0xFFF0F0F3)
val LightCard = Color(0xFFFBFBFC)
val LightFloating = Color(0xFFFFFFFF)
val LightContainerLow = Color(0xFFF5F5F7)
val LightContainer = Color(0xFFEBEBEF)
val LightContainerHigh = Color(0xFFE3E3E8)
val LightContainerHighest = Color(0xFFD8D8DE)
val LightInk = Color(0xFF1D1D1F)
val LightInkSecondary = Color(0xFF6E6E73)
val JiumiLightBackground = LightCanvas

// Dark theme
val DarkCanvas = Color(0xFF000000)
val DarkCard = Color(0xFF1C1C1E)
val DarkContainerHigh = Color(0xFF2C2C2E)
val DarkContainerHighest = Color(0xFF3A3A3C)
val DarkSeparator = Color(0xFF38383A)
val DarkInk = Color(0xFFF5F5F7)
val DarkInkSecondary = Color(0xFF98989F)

// Charts
val ChartPurple = Color(0xFF6B5FD3)
val ChartPurpleDark = Color(0xFFA79DFF)
val ChartPurpleFaint = Color(0xFFE4E1F6)
val ChartPurpleFaintDark = Color(0xFF34305A)
