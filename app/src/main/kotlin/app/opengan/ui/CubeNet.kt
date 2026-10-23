package app.opengan.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.opengan.core.cube.CubieCube

/** Grid position (column, row) of each face in the unfolded net, in URFDLB order. */
private val FACE_ORIGINS = listOf(3 to 0, 6 to 3, 3 to 3, 3 to 6, 0 to 3, 9 to 3)

/** Flat 2D net of the cube, 12 x 9 stickers. Stickers fade to their new colour as the cube turns. */
@Composable
fun CubeNet(cube: CubieCube, modifier: Modifier = Modifier) {
    val facelets = cube.toFacelets()
    val colors = List(54) { i ->
        animateColorAsState(CubeColors.byFace[facelets[i]] ?: Color.Gray, tween(150), label = "sticker").value
    }
    Canvas(modifier.aspectRatio(12f / 9f)) {
        val cell = size.width / 12f
        val gap = cell * 0.08f
        for (face in 0 until 6) {
            val (ox, oy) = FACE_ORIGINS[face]
            // Black plastic behind each face, like a real cube.
            drawRoundRect(
                color = Color(0xFF1A1A1A),
                topLeft = Offset(ox * cell, oy * cell),
                size = Size(cell * 3, cell * 3),
                cornerRadius = CornerRadius(gap * 3f),
            )
            for (i in 0 until 9) {
                drawRoundRect(
                    color = colors[face * 9 + i],
                    topLeft = Offset((ox + i % 3) * cell + gap, (oy + i / 3) * cell + gap),
                    size = Size(cell - 2 * gap, cell - 2 * gap),
                    cornerRadius = CornerRadius(gap * 1.5f),
                )
            }
        }
    }
}
