package smartbin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import smartbin.notify.Feel
import smartbin.notify.Sfx
import smartbin.notify.Sounds

/** The mockup sizes text in em on a 14 px base. */
fun em(x: Float): TextUnit = (14f * x).sp

/** Text in the mockup's type scale. **bold** marks the parts .hint b makes darker. */
@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 1f,
    weight: FontWeight = FontWeight.ExtraBold,
    color: Color = LocalContentColor.current,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    tnum: Boolean = false,
    spacing: Float = 0f,
    lineHeight: Float = 1.35f,
    bold: Color? = null,
    /** Steps the size down until it fits one line, the mockup's container-query labels. */
    fit: Boolean = false,
) {
    var scale by remember(text.length, size) { mutableFloatStateOf(1f) }
    val shown = if (fit) size * scale else size
    val body: AnnotatedString = if ("**" in text) marked(text, bold ?: color) else AnnotatedString(text)
    Text(
        text = body,
        modifier = modifier,
        color = color,
        textAlign = align,
        maxLines = if (fit) 1 else maxLines,
        softWrap = !fit && maxLines != 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (fit && it.didOverflowWidth && scale > 0.6f) scale = (scale * 0.92f).coerceAtLeast(0.6f) },
        style = TextStyle(
            fontFamily = Inter,
            fontSize = em(shown),
            fontWeight = weight,
            lineHeight = em(shown * lineHeight),
            letterSpacing = (spacing * 14f * shown).sp,
            fontFeatureSettings = if (tnum) "tnum" else null,
        ),
    )
}

private fun marked(text: String, bold: Color): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = bold, fontWeight = FontWeight.ExtraBold)) { append(part) } else append(part)
    }
}

/** .hint */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    val c = Sb.c
    T(text, modifier, size = 0.8f, weight = FontWeight.Medium, color = c.ink2, bold = c.ink, align = align, lineHeight = 1.45f)
}

/** .sh, the small uppercase section heading. */
@Composable
fun Sh(icon: ImageVector, text: String, cc: Color = Sb.c.ink2, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(icon, 16.dp, cc)
        T(text.uppercase(), size = 0.76f, spacing = 0.1f, color = cc, maxLines = 1)
    }
}

/** A press with no ripple and the key's own sound and tick. */
@Composable
fun Modifier.press(enabled: Boolean = true, sound: Sfx = Sfx.TAP, src: MutableInteractionSource? = null, onClick: () -> Unit): Modifier {
    val view = LocalView.current
    return clickable(interactionSource = src ?: remember { MutableInteractionSource() }, indication = null, enabled = enabled) {
        Feel.play(view, sound)
        onClick()
    }
}

/** Swallows taps, so a sheet does not pass them to the page behind it. */
@Composable
fun Modifier.swallow(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

enum class Look { PLAIN, TINT, SOLID, ACCENT, DANGER }

/**
 * .btn and its kinds. PLAIN is a raised key on the page colour; TINT adds a
 * ring and colours the label; SOLID and ACCENT fill it; DANGER is the brutal
 * red key. Pressed, a key sinks into the page. [stack] puts the icon above
 * the label, as on the bin card's three action keys.
 */
@Composable
fun Btn(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    look: Look = Look.PLAIN,
    cc: Color = Sb.c.ink2,
    small: Boolean = false,
    enabled: Boolean = true,
    stack: Boolean = false,
    sound: Sfx = Sfx.TAP,
    /** For a key that stays pushed in, such as a picked bin. */
    pressedIn: Boolean = false,
    iconMod: Modifier = Modifier,
    textSize: Float? = null,
) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val down = (pressed && enabled) || pressedIn
    val radius = if (look == Look.DANGER) 7.dp else if (small) 11.dp else 13.dp
    val shape = RoundedCornerShape(radius)
    val kind = if (enabled) look else Look.PLAIN
    val fg = when {
        !enabled -> c.ink2
        kind == Look.SOLID || kind == Look.ACCENT -> c.onAccent
        kind == Look.DANGER -> Color.White
        kind == Look.TINT || pressedIn -> cc
        else -> c.ink
    }
    val iconTint = when {
        !enabled -> c.ink2
        kind == Look.PLAIN && !pressedIn -> cc.takeIf { it != c.ink2 } ?: c.ink
        else -> fg
    }
    val fill = when (kind) {
        Look.SOLID -> cc
        Look.ACCENT -> c.accent
        Look.DANGER -> c.bad
        else -> c.base
    }
    val ring = if (enabled && (kind == Look.TINT || pressedIn)) cc.soft(0.3f) else null
    val surface = if (kind == Look.DANGER) {
        Modifier
            .offset { if (down) IntOffset(3.dp.roundToPx(), 3.dp.roundToPx()) else IntOffset.Zero }
            .brutal(c, radius, fill, if (down) 1.dp else 4.dp)
    } else if (down) {
        Modifier
            .offset { IntOffset(0, if (pressed) 1.dp.roundToPx() else 0) }
            .then(if (ring != null) Modifier.ring(ring, radius) else Modifier)
            .clip(shape).background(fill).sunk(c, radius)
    } else {
        Modifier.raised(c, radius, Out.O1, ring).clip(shape).background(fill)
    }
    val minH = if (stack) 56.dp else if (small) 38.dp else 44.dp
    val size = textSize ?: if (stack) 0.76f else if (small) 0.8f else 0.88f
    val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (icon != null) Ico(icon, if (stack) 22.dp else if (small) 15.dp else 18.dp, iconTint, iconMod)
            if (text != null) T(text, size = size, weight = FontWeight.Bold, spacing = 0.02f, maxLines = if (stack) 2 else 1, align = TextAlign.Center, lineHeight = 1.1f)
        }
    }
    val base = modifier
        .alpha(if (enabled) 1f else 0.4f)
        .then(surface)
        .press(enabled, sound, src, onClick)
        .defaultMinSize(minHeight = minH)
    if (stack) {
        Column(
            base.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) { content() }
    } else {
        Row(
            base.padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 5.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** A square key with one icon: .iconbtn, .cardbtn, .gearbtn. */
@Composable
fun IconKey(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Sb.c.accent,
    size: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    radius: Dp = 13.dp,
    enabled: Boolean = true,
    sound: Sfx = Sfx.TAP,
    label: String? = null,
    iconMod: Modifier = Modifier,
) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .then(if (pressed) Modifier.clip(shape).background(c.base).sunk(c, radius) else Modifier.bump(c, radius))
            .press(enabled, sound, src, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val t = if (pressed && label != null) c.accent else tint
        Ico(icon, iconSize, t, iconMod)
        if (label != null) T(label, size = 8f / 14f, spacing = 0.05f, color = t, maxLines = 1)
    }
}

/** .tag, the small white-on-colour label. */
@Composable
fun Tag(text: String, bg: Color, modifier: Modifier = Modifier, edge: Boolean = true) {
    val c = Sb.c
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (edge) Modifier.drawBehind { drawRoundRect(c.ink, cornerRadius = CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) } else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) { T(text, size = 0.7f, spacing = 0.05f, color = Color.White, maxLines = 1) }
}

/** .tgl, the switch. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Sb.c
    val x by animateFloatAsState(if (on) 1f else 0f, tween(220), label = "tgl")
    Box(
        Modifier
            .size(54.dp, 32.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) c.accent else c.well)
            .sunk(c, 16.dp)
            .press(enabled, if (on) Sfx.SWITCH_OFF else Sfx.SWITCH_ON) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset((22.dp.toPx() * x).toInt(), 0) }
                .size(24.dp)
                .bump(c, 12.dp),
        )
    }
}

/** .twoway: a sunk tray of choices. Use [Tw] inside. */
@Composable
fun TwoWay(modifier: Modifier = Modifier, full: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val c = Sb.c
    Row(
        (if (full) modifier.fillMaxWidth() else modifier).well(c, 13.dp).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** .tw, one choice in a [TwoWay]. */
@Composable
fun RowScope.Tw(
    on: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    cc: Color = Sb.c.accent,
    enabled: Boolean = true,
    grow: Float? = 1f,
    stack: Boolean = false,
    minHeight: Dp = 38.dp,
    textSize: Float = 0.78f,
) {
    val c = Sb.c
    val shape = RoundedCornerShape(9.dp)
    val fg = if (on) c.onAccent else c.ink2
    val m = Modifier
        .then(if (grow != null) Modifier.weight(grow) else Modifier)
        .defaultMinSize(minHeight = minHeight)
        .alpha(if (enabled) 1f else 0.42f)
        .then(if (on) Modifier.bump(c, 9.dp, fill = cc) else Modifier.clip(shape))
        .press(enabled && !on, Sfx.CHOICE, onClick = onClick)
        .padding(horizontal = if (stack) 2.dp else 9.dp, vertical = if (stack) 5.dp else 0.dp)
    val inner: @Composable () -> Unit = {
        if (icon != null) Ico(icon, if (stack) 26.dp else 17.dp, fg)
        if (label != null) T(label, size = textSize, weight = FontWeight.Bold, color = fg, fit = true, align = TextAlign.Center)
    }
    if (stack) Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) { inner() }
    else Row(m, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { inner() }
}

/** .chip on the bin card. */
@Composable
fun Chip(icon: ImageVector, text: String, on: Boolean, cc: Color, modifier: Modifier = Modifier, iconMod: Modifier = Modifier) {
    val c = Sb.c
    Row(
        modifier
            .height(30.dp)
            .alpha(if (on) 1f else 0.38f)
            .bump(c, 9.dp, ring = if (on) cc.soft(0.3f) else null)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val t = if (on) cc else c.ink2
        Ico(icon, 13.dp, t, iconMod)
        T(text, size = 0.72f, spacing = 0.02f, color = t, maxLines = 1)
    }
}

/** .field: a label line over its control. */
@Composable
fun Field(label: String, icon: ImageVector? = null, cc: Color = Sb.c.ink2, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Ico(icon, 15.dp, cc)
            T(label, size = 0.76f, spacing = 0.03f, color = cc)
        }
        content()
    }
}

/** .well with − and + keys: the stepper. */
@Composable
fun Stepper(value: String, unit: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, cc: Color = Sb.c.accent) {
    val c = Sb.c
    Row(
        modifier.fillMaxWidth().well(c, 13.dp).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepKey("−", enabled, Sfx.STEP_DOWN, cc) { onStep(-1) }
        T(value, Modifier.weight(1f), size = 1.25f, tnum = true, align = TextAlign.Center, maxLines = 1)
        T(unit, Modifier.padding(end = 2.dp), size = 0.76f, color = c.ink2, maxLines = 1)
        StepKey("+", enabled, Sfx.STEP_UP, cc) { onStep(1) }
    }
}

@Composable
private fun StepKey(label: String, enabled: Boolean, sound: Sfx, cc: Color, onClick: () -> Unit) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .size(44.dp)
            .then(if (pressed) Modifier.clip(RoundedCornerShape(11.dp)).background(c.base).sunk(c, 11.dp) else Modifier.bump(c, 11.dp))
            .press(enabled, sound, src, onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, size = 1.3f, color = if (pressed) cc else c.ink2, lineHeight = 1f) }
}

/** .readout, a value with no control. */
@Composable
fun Readout(text: String) {
    val c = Sb.c
    Box(Modifier.fillMaxWidth().well(c, 12.dp).padding(horizontal = 14.dp, vertical = 11.dp)) { T(text, tnum = true) }
}

/** .inp, a text field sunk into the page. */
@Composable
fun Inp(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val c = Sb.c
    val style = TextStyle(fontFamily = Inter, fontSize = em(1.02f), fontWeight = FontWeight.Bold, color = c.ink)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .well(c, 12.dp),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 13.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, size = 1.02f, weight = FontWeight.Bold, color = c.ink2.soft(0.7f), maxLines = 1)
                inner()
            }
        },
    )
}

enum class Tone { RED, PURPLE, TEAL, GREEN, AMBER, ORANGE }

/** .banner: the brutal message block. */
@Composable
fun Banner(tone: Tone, icon: ImageVector, title: String, small: String? = null, modifier: Modifier = Modifier, iconMod: Modifier = Modifier) {
    val c = Sb.c
    val bg = when (tone) {
        Tone.RED -> c.bad; Tone.PURPLE -> c.sim; Tone.TEAL -> c.cal; Tone.GREEN -> c.ok; Tone.AMBER -> c.warn; Tone.ORANGE -> c.cloud
    }
    val fg = if (c.isDark || tone == Tone.AMBER) (if (c.isDark) Color(0xFF13100D) else Color(0xFF241C10)) else Color.White
    Row(
        modifier.fillMaxWidth().brutal(c, 8.dp, bg).padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ico(icon, 20.dp, fg, iconMod)
        Column(Modifier.weight(1f)) {
            T(title, size = 0.88f, weight = FontWeight.Bold, color = fg)
            if (!small.isNullOrBlank()) T(small, Modifier.padding(top = 2.dp).alpha(0.92f), size = 0.79f, weight = FontWeight.SemiBold, color = fg)
        }
    }
}

/** .pcard.nm: the plain raised card. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 14.dp, gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Sb.c
    Column(modifier.fillMaxWidth().nm(c).padding(padding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** .lrow: a row of the App section. */
@Composable
fun LRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    cc: Color = Sb.c.ink2,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    sound: Sfx = Sfx.OPEN,
    iconMod: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .then(if (pressed) Modifier.clip(RoundedCornerShape(14.dp)).background(c.base).sunk(c, 14.dp) else Modifier.bump(c, 14.dp))
            .then(if (onClick != null) Modifier.press(true, sound, src, onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ico(icon, 21.dp, cc, iconMod)
        Column(Modifier.weight(1f)) {
            T(title, weight = FontWeight.Bold)
            if (sub != null) T(sub, Modifier.padding(top = 1.dp), size = 0.8f, weight = FontWeight.SemiBold, color = c.ink2)
        }
        trailing?.invoke(this)
        if (chevron) Ico(Ic.chev, 18.dp, c.ink2, Modifier.graphicsLayer { rotationZ = -90f })
    }
}

/** .opt: one line of a pick list, with its radio. */
@Composable
fun Opt(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Sb.c
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .then(if (on) Modifier.clip(RoundedCornerShape(13.dp)).background(c.base).sunk(c, 13.dp) else Modifier.bump(c, 13.dp))
            .press(true, Sfx.CHOICE, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).well(c, 11.dp), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        }
        CompositionLocalProvider(LocalContentColor provides if (on) c.accent else c.ink) { content() }
    }
}

/** .scrim and .sheet: slides up from the bottom; a tap outside or Back closes it. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Sb.c
    val slide = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(180)) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(240)) }
    LaunchedEffect(Unit) { Sounds.playIfQuiet(Sfx.OPEN) }
    val view = LocalView.current
    BackHandler { Feel.play(view, Sfx.BACK); onDismiss() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().zIndex(40f)) {
        val maxH = maxHeight * 0.82f
        Box(
            Modifier
                .fillMaxSize()
                .alpha(fade.value)
                .background(Color(0x800A0806))
                .press(true, Sfx.BACK, onClick = onDismiss),
        )
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxH)
                .graphicsLayer { translationY = slide.value * size.height }
                .drawBehind {
                    drawRoundRect(Color(0x47000000), topLeft = Offset(0f, -6.dp.toPx()), size = size, cornerRadius = CornerRadius(24.dp.toPx()))
                }
                .clip(shape)
                .background(c.base)
                .swallow()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(42.dp, 5.dp).clip(CircleShape).background(c.ink2.soft(0.4f)))
            CompositionLocalProvider(LocalContentColor provides c.ink) { content() }
        }
    }
}

@Composable
fun SheetTitle(icon: ImageVector?, text: String, center: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = Sb.c
    Row(
        if (center) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        if (icon != null) Ico(icon, 21.dp, c.accent)
        T(text, size = 1.1f, align = if (center) TextAlign.Center else null)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
fun SheetText(text: String, center: Boolean = false) {
    T(text, if (center) Modifier.fillMaxWidth() else Modifier, size = 0.9f, weight = FontWeight.Medium, align = if (center) TextAlign.Center else null)
}

/** .acts: two keys sharing a row. */
@Composable
fun Acts(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** .toast: an amber banner that rises over the bottom of the page. */
@Composable
fun BoxScope.Toast(message: String, bottom: Dp) {
    val rise = remember(message) { Animatable(0f) }
    LaunchedEffect(message) { rise.animateTo(1f, tween(250)) }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .zIndex(60f)
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = bottom)
            .widthIn(max = 520.dp)
            .graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * 8.dp.toPx()
            },
    ) { Banner(Tone.AMBER, Ic.bell, message) }
}

/** .sk: a breathing block while the first reading comes in. */
@Composable
fun Skeleton(height: Dp, modifier: Modifier = Modifier, radius: Dp = 18.dp) {
    val c = Sb.c
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "sk")
    Box(modifier.fillMaxWidth().height(height).alpha(a).well(c, radius))
}

/** The page's scrolling column, .scroll. */
@Composable
fun ScrollPage(modifier: Modifier = Modifier, state: androidx.compose.foundation.ScrollState = rememberScrollState(), bottomExtra: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(state)
            .padding(PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 20.dp + bottomExtra)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** .backbar: the back key and a title, for the pages under Settings. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconKey(Ic.back, onBack, sound = Sfx.BACK)
        T(title, Modifier.weight(1f), size = 1.12f)
        trailing()
    }
}

/** .btstate: the small status pill in a back bar. */
@Composable
fun StatePill(icon: ImageVector, text: String, cc: Color, on: Boolean) {
    val c = Sb.c
    Row(
        Modifier.height(34.dp).bump(c, 17.dp, ring = if (on) cc.soft(0.32f) else null).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val t = if (on) cc else c.ink2
        Ico(icon, 18.dp, t)
        T(text.uppercase(), size = 0.76f, spacing = 0.03f, color = t, maxLines = 1)
    }
}

/** A swatch for chart legends: solid, or dashed like the line it stands for. */
@Composable
fun Swatch(color: Color, dash: Int = 0) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp, 4.dp)) {
        val h = size.height
        if (dash == 0) {
            drawRoundRect(color, cornerRadius = CornerRadius(h / 2))
        } else {
            val on = (if (dash == 1) 5f else 2.5f) * density
            val gap = (if (dash == 1) 4f else 4f) * density
            var x = 0f
            while (x < size.width) {
                drawRect(color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(minOf(on, size.width - x), h))
                x += on + gap
            }
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))
