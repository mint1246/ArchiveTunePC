package com.materialkolor.ktx

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.palettes.TonalPalette

// Equivalent to MaterialKolor 5.0.0-alpha07 Color.toTonalPalette.
internal fun Color.toTonalPalette(): TonalPalette = TonalPalette.fromInt(toArgb())
