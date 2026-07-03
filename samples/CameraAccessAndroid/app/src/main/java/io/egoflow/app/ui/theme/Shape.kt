package io.egoflow.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Aligned with web frontend radius tokens from styles.css:
// --radius: 0.625rem (10px), --radius-sm: 6px, --radius-md: 8px, --radius-xl: 14px
// Cards use rounded-2xl (16px)
val EgoFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
