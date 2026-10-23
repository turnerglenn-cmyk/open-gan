package app.opengan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

private class Piece(
    val x: Float, val vx: Float, val vy: Float,
    val spin: Float, val size: Float, val color: Color,
)

/**
 * A burst of cube-coloured confetti, played once each time [key] changes
 * (pass null to show nothing). Doesn't take touches.
 */
@Composable
fun Confetti(key: Any?, modifier: Modifier = Modifier, pieces: Int = 90) {
    if (key == null) return
    val progress = remember(key) { Animatable(0f) }
    val burst = remember(key) {
        val r = Random(key.hashCode())
        List(pieces) {
            Piece(
                x = r.nextFloat(),
                vx = (r.nextFloat() - 0.5f) * 0.6f,
                vy = 0.2f + r.nextFloat() * 0.5f,
                spin = (r.nextFloat() - 0.5f) * 1440f,
                size = 0.012f + r.nextFloat() * 0.016f,
                color = CubeColors.bright[r.nextInt(CubeColors.bright.size)],
            )
        }
    }
    LaunchedEffect(key) { progress.animateTo(1f, tween(2600, easing = LinearEasing)) }
    val t = progress.value
    if (t >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val fade = if (t > 0.75f) (1f - t) / 0.25f else 1f
        for (p in burst) {
            val x = (p.x + p.vx * t) * size.width
            val y = (-0.05f + p.vy * t + 0.9f * t * t) * size.height
            val side = p.size * size.width
            rotate(p.spin * t, pivot = Offset(x, y)) {
                drawRect(
                    p.color.copy(alpha = fade),
                    topLeft = Offset(x - side / 2, y - side / 3),
                    size = Size(side, side * 0.66f),
                )
            }
        }
    }
}
