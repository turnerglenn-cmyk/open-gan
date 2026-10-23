package app.opengan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The six sticker colours, used everywhere in the app's look. */
object CubeColors {
    val White = Color(0xFFFFFFFF)
    val Red = Color(0xFFE53935)
    val Green = Color(0xFF2EB82E)
    val Yellow = Color(0xFFFFD12E)
    val Orange = Color(0xFFFF8A1F)
    val Blue = Color(0xFF2F6BFF)

    /** By face letter, standard scheme: white top, green front. */
    val byFace = mapOf('U' to White, 'R' to Red, 'F' to Green, 'D' to Yellow, 'L' to Orange, 'B' to Blue)

    /** The colourful five, for confetti and accents. */
    val bright = listOf(Red, Green, Yellow, Orange, Blue)
}

private val Light = lightColorScheme(
    primary = CubeColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A7A),
    secondary = CubeColors.Orange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6CC),
    onSecondaryContainer = Color(0xFF5A2A00),
    tertiary = CubeColors.Green,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F5D5),
    onTertiaryContainer = Color(0xFF0B3D0B),
    error = CubeColors.Red,
    background = Color(0xFFFFFBF2),
    surface = Color(0xFFFFFBF2),
    surfaceVariant = Color(0xFFF1ECE0),
    surfaceContainer = Color(0xFFF6F1E6),
    surfaceContainerHigh = Color(0xFFF1ECE0),
    surfaceContainerHighest = Color(0xFFEBE5D8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AACFF),
    onPrimary = Color(0xFF0A2A7A),
    primaryContainer = Color(0xFF1C3F99),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFFFB066),
    onSecondary = Color(0xFF4A2300),
    secondaryContainer = Color(0xFF7A3D00),
    onSecondaryContainer = Color(0xFFFFE6CC),
    tertiary = Color(0xFF7DDC7D),
    onTertiary = Color(0xFF0B3D0B),
    tertiaryContainer = Color(0xFF1C661C),
    onTertiaryContainer = Color(0xFFD5F5D5),
    error = Color(0xFFFF8A80),
    background = Color(0xFF14161F),
    surface = Color(0xFF14161F),
    surfaceVariant = Color(0xFF262A38),
    surfaceContainer = Color(0xFF1C1F2B),
    surfaceContainerHigh = Color(0xFF232736),
    surfaceContainerHighest = Color(0xFF2B3041),
)

private val Fun = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Black),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

private val Rounded = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun OpenGanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Fun,
        shapes = Rounded,
        content = content,
    )
}

/** A small solved cube face in the six colours: the app's logo mark. */
@Composable
fun CubeMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val colors = listOf(
        CubeColors.Red, CubeColors.Green, CubeColors.Blue,
        CubeColors.Yellow, CubeColors.White, CubeColors.Orange,
        CubeColors.Blue, CubeColors.Orange, CubeColors.Red,
    )
    Canvas(modifier.size(size)) {
        val cell = this.size.width / 3f
        val gap = cell * 0.1f
        drawRoundRect(Color(0xFF1A1A1A), cornerRadius = CornerRadius(cell * 0.45f))
        for (i in 0 until 9) {
            drawRoundRect(
                colors[i],
                topLeft = Offset((i % 3) * cell + gap, (i / 3) * cell + gap),
                size = Size(cell - 2 * gap, cell - 2 * gap),
                cornerRadius = CornerRadius(gap * 2f),
            )
        }
    }
}
