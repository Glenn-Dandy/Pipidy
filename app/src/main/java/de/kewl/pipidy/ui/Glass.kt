package de.kewl.pipidy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colours of the dark glass look. */
object Ink {
    val Night = Color(0xFF04060B)
    val Deep = Color(0xFF0B1020)
    val Text = Color(0xFFF4F6FB)
    val Dim = Color(0xA6F4F6FB)
    val Faint = Color(0x61F4F6FB)
    val Amber = Color(0xFFFFB238)
    val OnAmber = Color(0xFF171004)
    val Violet = Color(0xFF6C5CFF)
    val Cyan = Color(0xFF19D3C5)
    val Red = Color(0xFFFF5C6C)
}

object Type {
    val Wordmark = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 6.sp, color = Ink.Dim)
    val Display = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp, color = Ink.Text)
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, color = Ink.Text)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Ink.Text)
    val Small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Ink.Dim)
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp, color = Ink.Dim)
    val Mono = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Ink.Text)
}

@Composable
fun Txt(
    text: String,
    style: TextStyle = Type.Body,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color.isSpecified) style.copy(color = color) else style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) =
    Txt(text.uppercase(), Type.Label, modifier, color)

/** Turns a label on its side, reading upwards or downwards, and reserves the turned size. */
fun Modifier.sideways(upwards: Boolean): Modifier = this
    .layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        layout(placeable.height, placeable.width) {
            placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
        }
    }
    .rotate(if (upwards) -90f else 90f)

/** Dark backdrop with soft colour glows and a fine grid, so the glass panels have something to sit on. */
@Composable
fun Backdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Ink.Deep, Ink.Night)))
                glow(Ink.Amber, Offset(size.width * 1.0f, size.height * 0.06f), size.width * 0.95f, 0.30f)
                glow(Ink.Violet, Offset(size.width * -0.1f, size.height * 0.40f), size.width * 1.05f, 0.36f)
                glow(Ink.Cyan, Offset(size.width * 0.9f, size.height * 0.92f), size.width * 0.95f, 0.22f)
                val step = 28.dp.toPx()
                val line = Color.White.copy(alpha = 0.028f)
                var x = step
                while (x < size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height))
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y))
                    y += step
                }
            },
        content = content,
    )
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius),
        radius,
        center,
    )
}

/** A sharp-edged pane of glass: translucent fill and a thin edge that catches light at the top left. */
fun Modifier.glass(tint: Color = Color.White, strength: Float = 1f): Modifier = this
    .background(
        Brush.linearGradient(
            listOf(tint.copy(alpha = (0.15f * strength).coerceAtMost(1f)), tint.copy(alpha = (0.045f * strength).coerceAtMost(1f)))
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.08f), tint.copy(alpha = 0.26f))),
        RectangleShape,
    )

/** Soft light spilling out from under an active element. */
fun Modifier.halo(color: Color, visible: Boolean): Modifier =
    if (!visible) this else drawBehind {
        val radius = size.maxDimension * 0.62f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.24f), Color.Transparent), center, radius),
            radius,
            center,
        )
    }

@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(tint)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        title?.let { Label(it) }
        content()
    }
}

/** Screen frame: fixed header, loading line, scrolling content. */
@Composable
fun Page(
    busy: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) { header() }
        BusyLine(busy)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { message ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(Ink.Red, 1.4f)
                        .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Txt(message, Type.Body, Modifier.weight(1f).padding(vertical = 8.dp))
                    GlassButton("OK", onDismissError)
                }
            }
            content()
        }
    }
}

/** Header of a sub screen: back, title, actions. */
@Composable
fun TitleBar(title: String, kicker: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconSquare(Glyph.Back, "Zurück", onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Label(kicker)
            Txt(title, Type.Title, maxLines = 1)
        }
        actions()
    }
}

@Composable
private fun BusyLine(active: Boolean) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (active) {
            val phase by rememberInfiniteTransition(label = "busy").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
                label = "busy",
            )
            Canvas(Modifier.matchParentSize()) {
                val width = size.width * 0.4f
                val start = (size.width + width) * phase - width
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Ink.Amber, Color.Transparent),
                        startX = start,
                        endX = start + width,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(width, size.height),
                )
            }
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .then(
                if (primary) Modifier.background(Ink.Amber.copy(alpha = if (pressed) 0.75f else 1f))
                else Modifier.glass(tint, if (pressed) 2.2f else 1f)
            )
            .clickable(source, null, enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, color = if (primary) Ink.OnAmber else Ink.Text)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .then(if (selected) Modifier.background(Ink.Amber) else Modifier.glass(strength = if (pressed) 2.2f else 1f))
            .clickable(source, null, enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Txt(text, Type.Small, color = if (selected) Ink.OnAmber else Ink.Text)
    }
}

enum class Glyph { Back, Plus, Power, Refresh, Tune }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val w = size.minDimension
        val width = w * 0.09f
        val stroke = Stroke(width, cap = StrokeCap.Square)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), width, StrokeCap.Square)
        when (glyph) {
            Glyph.Plus -> {
                line(0.5f, 0.14f, 0.5f, 0.86f)
                line(0.14f, 0.5f, 0.86f, 0.5f)
            }
            Glyph.Back -> drawPath(
                Path().apply {
                    moveTo(w * 0.62f, w * 0.18f)
                    lineTo(w * 0.30f, w * 0.5f)
                    lineTo(w * 0.62f, w * 0.82f)
                },
                color,
                style = stroke,
            )
            Glyph.Power -> {
                drawArc(color, -55f, 290f, false, Offset(w * 0.14f, w * 0.18f), Size(w * 0.72f, w * 0.72f), style = stroke)
                line(0.5f, 0.06f, 0.5f, 0.46f)
            }
            Glyph.Refresh -> {
                val radius = w * 0.32f
                val middle = Offset(w * 0.5f, w * 0.54f)
                drawArc(color, 30f, 255f, false, middle - Offset(radius, radius), Size(radius * 2, radius * 2), style = stroke)
                // Arrow head at the end of the arc, pointing along the direction of travel.
                val angle = Math.toRadians(285.0)
                val out = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val along = Offset(-out.y, out.x)
                val end = middle + out * radius
                drawPath(
                    Path().apply {
                        moveTo(end.x + along.x * w * 0.26f, end.y + along.y * w * 0.26f)
                        lineTo(end.x + out.x * w * 0.17f, end.y + out.y * w * 0.17f)
                        lineTo(end.x - out.x * w * 0.17f, end.y - out.y * w * 0.17f)
                        close()
                    },
                    color,
                )
            }
            Glyph.Tune -> {
                listOf(0.24f to 0.64f, 0.5f to 0.32f, 0.76f to 0.56f).forEach { (y, knob) ->
                    line(0.12f, y, 0.88f, y)
                    drawRect(color, Offset(w * (knob - 0.08f), w * (y - 0.09f)), Size(w * 0.16f, w * 0.18f))
                }
            }
        }
    }
}

@Composable
fun IconSquare(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 44.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(side)
            .glass(strength = if (pressed) 2.2f else 1f)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Ink.Text)
    }
}

/** Rectangular switch. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 28.dp else 4.dp, label = "toggle")
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .size(56.dp, 30.dp)
            .halo(Ink.Amber, checked)
            .glass(if (checked) Ink.Amber else Color.White, if (checked) 1.8f else 1f)
            .toggleable(checked, remember { MutableInteractionSource() }, null, enabled, Role.Switch, onChange),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 4.dp)
                .size(24.dp, 22.dp)
                .background(if (checked) Ink.Amber else Ink.Dim)
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.Body, Modifier.weight(1f))
        Toggle(checked, onChange, enabled = enabled)
    }
}

/** Flat slider: a bar that fills up and a slim upright handle. Reports the new value when the finger lifts. */
@Composable
fun ValueSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String,
    onDone: (Int) -> Unit,
    enabled: Boolean = true,
    track: Brush? = null,
) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val done by rememberUpdatedState(onDone)
    Column(Modifier.alpha(if (enabled) 1f else 0.35f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Txt(label, Type.Body, Modifier.weight(1f))
            Txt("${position.roundToInt()}$unit", Type.Mono, color = Ink.Dim)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(min, max, enabled) {
                    if (!enabled) return@pointerInput
                    fun at(x: Float) = min + (x / size.width).coerceIn(0f, 1f) * (max - min)
                    detectTapGestures { offset ->
                        position = at(offset.x)
                        done(position.roundToInt())
                    }
                }
                .pointerInput(min, max, enabled) {
                    if (!enabled) return@pointerInput
                    fun at(x: Float) = min + (x / size.width).coerceIn(0f, 1f) * (max - min)
                    detectHorizontalDragGestures(
                        onDragStart = { position = at(it.x) },
                        onDragEnd = { done(position.roundToInt()) },
                        onDragCancel = { done(position.roundToInt()) },
                    ) { change, _ ->
                        position = at(change.position.x)
                        change.consume()
                    }
                }
        ) {
            val bar = 6.dp.toPx()
            val top = (size.height - bar) / 2
            val fraction = ((position - min) / (max - min)).coerceIn(0f, 1f)
            val x = size.width * fraction
            if (track != null) {
                drawRect(track, Offset(0f, top), Size(size.width, bar))
            } else {
                drawRect(Color.White.copy(alpha = 0.14f), Offset(0f, top), Size(size.width, bar))
                drawRect(
                    Brush.horizontalGradient(listOf(Ink.Amber.copy(alpha = 0.35f), Ink.Amber), endX = x.coerceAtLeast(1f)),
                    Offset(0f, top),
                    Size(x, bar),
                )
            }
            val handle = Size(5.dp.toPx(), 26.dp.toPx())
            drawRect(
                Color.White,
                Offset((x - handle.width / 2).coerceIn(0f, size.width - handle.width), (size.height - handle.height) / 2),
                handle,
            )
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    error: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val style = if (mono) Type.Mono else Type.Body
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Ink.Amber),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == KeyboardType.Text && !mono) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glass(if (error) Ink.Red else Color.White)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    if (value.isEmpty()) Txt(placeholder, style, color = Ink.Faint, maxLines = 1)
                    inner()
                }
            },
        )
    }
}
