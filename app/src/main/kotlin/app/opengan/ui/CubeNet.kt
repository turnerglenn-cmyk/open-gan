package app.opengan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.opengan.core.cube.CubieCube

/** Standard colour scheme: white top, green front. */
private val FACE_COLORS = mapOf(
    'U' to Color(0xFFFFFFFF), 'R' to Color(0xFFD32F2F), 'F' to Color(0xFF2E7D32),
    'D' to Color(0xFFFDD835), 'L' to Color(0xFFF57C00), 'B' to Color(0xFF1565C0),
)

/** Grid position (column, row) of each face in the unfolded net, in URFDLB order. */
private val FACE_ORIGINS = listOf(3 to 0, 6 to 3, 3 to 3, 3 to 6, 0 to 3, 9 to 3)

/** Flat 2D net of the cube, 12 x 9 stickers. */
@Composable
fun CubeNet(cube: CubieCube, modifier: Modifier = Modifier) {
    val facelets = cube.toFacelets()
    Canvas(modifier.aspectRatio(12f / 9f)) {
        val cell = size.width / 12f
        val gap = cell * 0.08f
        for (face in 0 until 6) {
            val (ox, oy) = FACE_ORIGINS[face]
            for (i in 0 until 9) {
                val color = FACE_COLORS[facelets[face * 9 + i]] ?: Color.Gray
                drawRoundRect(
                    color = color,
                    topLeft = Offset((ox + i % 3) * cell + gap, (oy + i / 3) * cell + gap),
                    size = Size(cell - 2 * gap, cell - 2 * gap),
                    cornerRadius = CornerRadius(gap * 1.5f),
                )
            }
        }
    }
}
