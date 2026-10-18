package com.bindlab.phone.ui

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand accents used for data visualisation regardless of the dynamic palette. */
object BindLabColors {
    val Teal = Color(0xFF2DD4BF)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Blue = Color(0xFF3B82F6)
    val TargetGrey = Color(0xFF6B7F99)
    val Surface = Color(0xFF0E1114)
    val SurfaceContainer = Color(0xFF161B20)
    val SurfaceContainerHigh = Color(0xFF1E252B)
    val OnSurface = Color(0xFFF3F4F6)
    val OnSurfaceVariant = Color(0xFFA3ACB8)
    val Outline = Color(0xFF3A434D)
    val Frost = Color(0xCC14181C)
    val FrostBorder = Color(0x22FFFFFF)
    val Offline = Color(0xFF6B7280)
}

private val FallbackDark = darkColorScheme(
    primary = BindLabColors.Teal,
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF0F4A43),
    onPrimaryContainer = Color(0xFFB2F5EA),
    secondary = BindLabColors.Amber,
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF4A3300),
    onSecondaryContainer = Color(0xFFFFE2B3),
    tertiary = BindLabColors.Blue,
    error = BindLabColors.Red,
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF5A1414),
    onErrorContainer = Color(0xFFFFDAD6),
    background = BindLabColors.Surface,
    onBackground = BindLabColors.OnSurface,
    surface = BindLabColors.Surface,
    onSurface = BindLabColors.OnSurface,
    surfaceVariant = BindLabColors.SurfaceContainerHigh,
    onSurfaceVariant = BindLabColors.OnSurfaceVariant,
    surfaceContainer = BindLabColors.SurfaceContainer,
    surfaceContainerLow = Color(0xFF12161A),
    surfaceContainerLowest = Color(0xFF0A0D10),
    surfaceContainerHigh = BindLabColors.SurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF262E36),
    outline = BindLabColors.Outline,
    outlineVariant = Color(0xFF2A3139),
)

val CardShape = RoundedCornerShape(28.dp)
val PillShape = RoundedCornerShape(999.dp)

private val BindLabShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = CardShape,
    extraLarge = CardShape,
)

private val BindLabTypography = Typography().let { t ->
    t.copy(
        headlineLarge = TextStyle(
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = t.displayMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BindLabTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current) else FallbackDark
    MaterialTheme(colorScheme = scheme, shapes = BindLabShapes, typography = BindLabTypography, content = content)
}
