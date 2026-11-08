package me.kavishdevar.librepods.presentation.screens.spatial

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One virtual speaker as drawn on the map. */
data class MapSpeaker(
    val name: String,
    val azimuth: Float,   // degrees, + = left
    val distance: Float,  // metres
    val levelDb: Float,
    val muted: Boolean,
    val solo: Boolean,
)

const val SUB_INDEX = 7
private const val MAX_DIST = 5f
private const val MIN_DIST = 0.5f

/**
 * Top-down view of the listener (nose up) with the seven virtual speakers around the head and
 * the sub band as a pad below it. Dot size and opacity follow the level; drag a dot to move it
 * (angle = azimuth, radius = distance), tap one to open its details. Positions animate when a
 * preset changes, but follow the finger directly while dragging.
 */
@Composable
fun SpatialHeadMap(
    speakers: List<MapSpeaker>,
    sub: MapSpeaker,
    selected: Int?,
    modifier: Modifier = Modifier,
    onMove: (index: Int, azimuth: Float, distance: Float) -> Unit,
    onTap: (index: Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val onSurface = colors.onSurface
    val ring = colors.onSurface.copy(alpha = 0.08f)
    val headFill = colors.surfaceContainerHighest
    val headEdge = colors.onSurface.copy(alpha = 0.25f)
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableIntStateOf(-1) }
    val latest by rememberUpdatedState(speakers)

    // Animated positions in metres (cartesian, so a wrap past 180 degrees does not swing round).
    val positions: List<State<Offset>> = speakers.mapIndexed { i, s ->
        animateOffsetAsState(
            targetValue = polar(s.azimuth, s.distance),
            animationSpec = if (dragging == i) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
            label = "spk$i",
        )
    }
    val levels: List<State<Float>> = (speakers + sub).mapIndexed { i, s ->
        animateFloatAsState(targetValue = if (s.muted) -30f else s.levelDb, label = "lvl$i")
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Virtual speaker map" }
            .pointerInput(Unit) {
                // Custom gesture: only a touch that starts on a speaker is ours, so the page
                // still scrolls when dragging on empty parts of the map.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val target = hit(down.position, w, h, latest) ?: return@awaitEachGesture
                    down.consume()
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!moved) onTap(target)
                            break
                        }
                        change.consume()
                        if (target == SUB_INDEX) continue
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            dragging = target
                        }
                        if (moved) {
                            val c = Offset(w / 2f, h / 2f)
                            val d = change.position - c
                            val az = Math.toDegrees(atan2(-d.x, -d.y).toDouble()).roundToInt().toFloat()
                            val dist = ((sqrt(d.x * d.x + d.y * d.y) / scale(w, h)).coerceIn(MIN_DIST, MAX_DIST) * 10f).roundToInt() / 10f
                            val cur = latest[target]
                            if (az != cur.azimuth || dist != cur.distance) onMove(target, az, dist)
                        }
                    }
                    dragging = -1
                }
            }
    ) {
        val c = center
        val scale = scale(size.width, size.height)

        // distance rings and axes
        for (m in 1..MAX_DIST.toInt()) {
            drawCircle(ring, radius = m * scale, center = c, style = Stroke(width = if (m == 2) 1.5f * density else density))
        }
        drawLine(ring, Offset(c.x, c.y - MAX_DIST * scale), Offset(c.x, c.y + MAX_DIST * scale), strokeWidth = density)
        drawLine(ring, Offset(c.x - MAX_DIST * scale, c.y), Offset(c.x + MAX_DIST * scale, c.y), strokeWidth = density)

        // listening cone towards the front
        val cone = Path().apply {
            moveTo(c.x, c.y)
            val r = MAX_DIST * scale
            lineTo(c.x - r * sin(Math.toRadians(30.0)).toFloat(), c.y - r * cos(Math.toRadians(30.0)).toFloat())
            lineTo(c.x + r * sin(Math.toRadians(30.0)).toFloat(), c.y - r * cos(Math.toRadians(30.0)).toFloat())
            close()
        }
        drawPath(cone, Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), center = c, radius = MAX_DIST * scale))

        // sound "rays" from each speaker to the head (subtle)
        speakers.forEachIndexed { i, s ->
            val p = c + positions[i].value * scale
            val alpha = levelAlpha(levels[i].value) * if (s.muted) 0.2f else 0.35f
            drawLine(
                accent.copy(alpha = alpha * 0.5f), p, c, strokeWidth = density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * density, 6 * density))
            )
        }

        drawHead(c, scale, headFill, headEdge, onSurface)

        // speakers
        speakers.forEachIndexed { i, s ->
            val p = c + positions[i].value * scale
            drawSpeaker(p, s, levels[i].value, i == selected || i == dragging, accent, onSurface, measurer)
        }

        // sub band pad
        val subCenter = subCenter(size.width, size.height)
        drawSub(subCenter, sub, levels[SUB_INDEX].value, selected == SUB_INDEX, accent, onSurface, measurer)

        // front marker
        val front = measurer.measure("FRONT", TextStyle(fontSize = 10.sp, color = onSurface.copy(alpha = 0.4f), letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium))
        drawText(front, topLeft = Offset(c.x - front.size.width / 2f, c.y - MAX_DIST * scale - front.size.height - 2 * density))
    }
}

private fun polar(azimuth: Float, distance: Float): Offset {
    val a = Math.toRadians(azimuth.toDouble())
    return Offset((-sin(a) * distance).toFloat(), (-cos(a) * distance).toFloat())
}

/** px per metre: MAX_DIST fits with room for labels. */
private fun scale(w: Float, h: Float): Float = min(w, h) * 0.42f / MAX_DIST

private fun subCenter(w: Float, h: Float): Offset = Offset(w * 0.88f, h * 0.92f)

private fun levelAlpha(db: Float): Float = (10f.pow(db / 20f)).coerceIn(0.25f, 1f)

private fun hit(p: Offset, w: Float, h: Float, speakers: List<MapSpeaker>): Int? {
    val c = Offset(w / 2f, h / 2f)
    val s = scale(w, h)
    val touch = min(w, h) * 0.07f
    var best: Int? = null
    var bestD = touch * touch
    speakers.forEachIndexed { i, sp ->
        val q = c + polar(sp.azimuth, sp.distance) * s
        val d = (q - p).getDistanceSquared()
        if (d < bestD) { bestD = d; best = i }
    }
    if (best == null && (subCenter(w, h) - p).getDistanceSquared() < (touch * 1.4f).pow(2)) return SUB_INDEX
    return best
}

private fun DrawScope.drawHead(c: Offset, scale: Float, fill: Color, edge: Color, onSurface: Color) {
    val r = (0.36f * scale).coerceAtLeast(18 * density)
    // shoulders
    drawOval(
        brush = Brush.verticalGradient(listOf(fill.copy(alpha = 0.55f), fill.copy(alpha = 0.15f)), startY = c.y, endY = c.y + r * 1.6f),
        topLeft = Offset(c.x - r * 2.1f, c.y - r * 0.15f),
        size = Size(r * 4.2f, r * 1.5f),
    )
    // ears
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(
            color = fill,
            topLeft = Offset(c.x + side * r * 0.98f - r * 0.16f, c.y - r * 0.28f),
            size = Size(r * 0.32f, r * 0.56f),
            cornerRadius = CornerRadius(r * 0.16f),
        )
        drawRoundRect(
            color = edge,
            topLeft = Offset(c.x + side * r * 0.98f - r * 0.16f, c.y - r * 0.28f),
            size = Size(r * 0.32f, r * 0.56f),
            cornerRadius = CornerRadius(r * 0.16f),
            style = Stroke(width = density),
        )
    }
    // nose
    val nose = Path().apply {
        moveTo(c.x - r * 0.2f, c.y - r * 0.9f)
        quadraticTo(c.x, c.y - r * 1.38f, c.x + r * 0.2f, c.y - r * 0.9f)
        close()
    }
    drawPath(nose, fill)
    drawPath(nose, edge, style = Stroke(width = density))
    // skull, slightly longer front-to-back, lit from the front
    drawOval(
        brush = Brush.radialGradient(
            listOf(fill, fill.copy(alpha = 0.92f)),
            center = Offset(c.x, c.y - r * 0.4f),
            radius = r * 1.4f,
        ),
        topLeft = Offset(c.x - r * 0.92f, c.y - r * 1.02f),
        size = Size(r * 1.84f, r * 2.04f),
    )
    drawOval(edge, topLeft = Offset(c.x - r * 0.92f, c.y - r * 1.02f), size = Size(r * 1.84f, r * 2.04f), style = Stroke(width = 1.2f * density))
    // hair line hint
    drawArc(
        color = onSurface.copy(alpha = 0.08f), startAngle = 200f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.7f), size = Size(r * 1.24f, r * 1.3f),
        style = Stroke(width = 2 * density, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawSpeaker(
    p: Offset, s: MapSpeaker, db: Float, highlighted: Boolean,
    accent: Color, onSurface: Color, measurer: TextMeasurer,
) {
    val a = levelAlpha(db)
    val base = 9f * density
    val radius = base * (0.75f + 0.5f * a) * if (highlighted) 1.2f else 1f
    val color = if (s.muted) onSurface.copy(alpha = 0.3f) else accent.copy(alpha = 0.35f + 0.65f * a)
    // halo shows level
    if (!s.muted) drawCircle(accent.copy(alpha = 0.12f * a), radius = radius * 2.2f, center = p)
    if (s.muted) drawCircle(color, radius = radius, center = p, style = Stroke(width = 2 * density))
    else drawCircle(color, radius = radius, center = p)
    if (highlighted) drawCircle(onSurface.copy(alpha = 0.9f), radius = radius + 3 * density, center = p, style = Stroke(width = 1.5f * density))
    if (s.solo) drawCircle(Color(0xFFFFC233), radius = radius + 6 * density, center = p, style = Stroke(width = 1.5f * density))
    val label = measurer.measure(
        s.name,
        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = onSurface.copy(alpha = if (s.muted) 0.4f else 0.85f))
    )
    drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - radius - label.size.height - 3 * density))
}

private fun DrawScope.drawSub(
    p: Offset, s: MapSpeaker, db: Float, highlighted: Boolean,
    accent: Color, onSurface: Color, measurer: TextMeasurer,
) {
    val a = levelAlpha(db)
    val r = 15f * density * (0.8f + 0.4f * a)
    val color = if (s.muted) onSurface.copy(alpha = 0.3f) else accent.copy(alpha = 0.3f + 0.6f * a)
    drawCircle(color.copy(alpha = color.alpha * 0.25f), radius = r * 1.5f, center = p)
    drawCircle(color, radius = r, center = p, style = Stroke(width = 3 * density))
    drawCircle(color, radius = r * 0.45f, center = p)
    if (highlighted) drawCircle(onSurface.copy(alpha = 0.9f), radius = r + 4 * density, center = p, style = Stroke(width = 1.5f * density))
    val label = measurer.measure("SUB", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = onSurface.copy(alpha = 0.7f)))
    drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y - r * 1.5f - label.size.height))
}
