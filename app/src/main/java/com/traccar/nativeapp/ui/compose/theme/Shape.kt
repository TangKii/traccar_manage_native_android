package com.traccar.nativeapp.ui.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner radius scale. 移植自 Jiumi（Apache-2.0）。 */
internal object AppShapes {
    val CardRadius = 16.dp
    val Small = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(10.dp)
    val Card = RoundedCornerShape(CardRadius)
    val Large = RoundedCornerShape(16.dp)
}

internal val JiumiShapes = Shapes(
    extraSmall = AppShapes.Control,
    small = AppShapes.Control,
    medium = AppShapes.Card,
    large = AppShapes.Large,
    extraLarge = RoundedCornerShape(24.dp)
)
