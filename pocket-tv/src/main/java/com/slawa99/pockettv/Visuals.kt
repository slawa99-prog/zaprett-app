package com.slawa99.pockettv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight native artwork: no animation loop, bitmap downloads or extra dependencies. */
internal object PocketColors {
    val background = Color.rgb(8, 15, 23)
    val card = Color.rgb(20, 34, 45)
    val ink = Color.rgb(241, 247, 250)
    val muted = Color.rgb(171, 194, 207)
    val mint = Color.rgb(53, 222, 216)
    val disabled = Color.rgb(103, 124, 137)
}

internal class AmbientBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var light: Shader? = null
    override fun onBoundsChange(bounds: Rect) {
        light = RadialGradient(bounds.width() * .88f, 0f, bounds.width() * .65f,
            intArrayOf(Color.rgb(12, 54, 65), PocketColors.background), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) { paint.shader = light; canvas.drawRect(bounds, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}

internal class PocketSurface(private val density: Float, private val hero: Boolean = false,
    private val navigation: Boolean = false, private val prominent: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fill: Shader? = null
    private var focused = false
    private var selected = false
    private var enabled = true
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        focused = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)
        selected = state.contains(android.R.attr.state_selected)
        enabled = state.contains(android.R.attr.state_enabled)
        updateFill(); invalidateSelf(); return true
    }
    override fun onBoundsChange(bounds: Rect) { updateFill() }
    private fun updateFill() {
        val colors = when {
            prominent && enabled -> intArrayOf(Color.rgb(53, 224, 220), Color.rgb(25, 178, 188))
            focused && enabled -> intArrayOf(Color.rgb(23, 83, 96), Color.rgb(17, 48, 61))
            selected && navigation -> intArrayOf(Color.rgb(24, 69, 82), Color.rgb(14, 47, 59))
            hero -> intArrayOf(Color.rgb(23, 54, 66), Color.rgb(11, 31, 42))
            else -> intArrayOf(Color.rgb(27, 45, 58), Color.rgb(16, 29, 39))
        }
        fill = LinearGradient(0f, bounds.top.toFloat(), bounds.width() * .5f, bounds.bottom.toFloat().coerceAtLeast(1f), colors, null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        val d = density; val radius = (if (hero) 14 else 11) * d
        rect.set(bounds.left + d, bounds.top + d, bounds.right - d, bounds.bottom - d)
        paint.style = Paint.Style.FILL; paint.shader = fill; paint.alpha = if (enabled || hero) 255 else 125
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null; paint.alpha = 255
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d * .65f
        paint.color = if (hero) Color.argb(95, 105, 176, 190) else Color.argb(65, 124, 169, 189)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (navigation && selected) {
            paint.style = Paint.Style.FILL; paint.color = PocketColors.mint
            canvas.drawRoundRect(RectF(rect.left, rect.top + 8*d, rect.left + 3*d, rect.bottom - 8*d), 1.5f*d, 1.5f*d, paint)
        }
        if (focused && enabled) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(45, 53, 222, 216); paint.strokeWidth = 5*d
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = if (prominent) PocketColors.ink else PocketColors.mint; paint.strokeWidth = 2*d
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal enum class PocketIcon { HOME, HELP, SETTINGS, YOUTUBE, SMARTTUBE, DOWNLOAD, REFRESH, POWER,
    CHEVRON, CHECK, CLOSE, LIST, SEARCH, LOG, INFO, COPY, SHARE, BACK, STOP, AUTO }

internal class PocketIconDrawable(var kind: PocketIcon, var symbolColor: Int = PocketColors.ink) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    private fun line(canvas: Canvas, vararg points: Float) {
        path.reset(); path.moveTo(points[0], points[1])
        var i = 2; while (i < points.size) { path.lineTo(points[i], points[i + 1]); i += 2 }
        canvas.drawPath(path, paint)
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = symbolColor; paint.alpha = opacity; paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        when (kind) {
            PocketIcon.HOME -> {
                line(canvas, 3f,10f,12f,3f,21f,10f)
                line(canvas, 5f,9f,5f,21f,10f,21f,10f,14f,14f,14f,14f,21f,19f,21f,19f,9f)
            }
            PocketIcon.SETTINGS -> {
                path.reset()
                for (i in 0..63) {
                    val angle = i * Math.PI / 32; val radius = if (i % 8 in 2..5) 10f else 8f
                    val x = 12 + radius * cos(angle).toFloat(); val y = 12 + radius * sin(angle).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); canvas.drawPath(path, paint); canvas.drawCircle(12f,12f,3.5f,paint)
            }
            PocketIcon.HELP -> {
                canvas.drawCircle(12f,12f,9f,paint)
                path.reset(); path.moveTo(9f,9f); path.cubicTo(9f,5f,16f,5f,15f,10f)
                path.cubicTo(14.7f,11.5f,12f,11.5f,12f,14f); canvas.drawPath(path,paint)
                canvas.drawPoint(12f,17f,paint)
            }
            PocketIcon.INFO -> { canvas.drawCircle(12f,12f,9f,paint); line(canvas,12f,11f,12f,17f); canvas.drawPoint(12f,7f,paint) }
            PocketIcon.YOUTUBE, PocketIcon.SMARTTUBE -> {
                if (kind == PocketIcon.YOUTUBE) {
                    paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 47, 51); paint.alpha = opacity
                    canvas.drawRoundRect(1f,4f,23f,20f,4f,4f,paint); paint.color = Color.WHITE
                } else canvas.drawRoundRect(2f,4f,22f,19f,2f,2f,paint)
                paint.style = Paint.Style.FILL; path.reset(); path.moveTo(10f,8f); path.lineTo(16f,12f); path.lineTo(10f,16f); path.close(); canvas.drawPath(path,paint)
                if (kind == PocketIcon.SMARTTUBE) { paint.style = Paint.Style.STROKE; line(canvas,9f,22f,15f,22f) }
            }
            PocketIcon.DOWNLOAD -> { line(canvas,12f,3f,12f,15f); line(canvas,7f,10f,12f,15f,17f,10f); line(canvas,3f,16f,3f,21f,21f,21f,21f,16f) }
            PocketIcon.POWER -> { canvas.drawArc(4f,4f,20f,20f,-45f,270f,false,paint); line(canvas,12f,2f,12f,12f) }
            PocketIcon.REFRESH, PocketIcon.AUTO -> { canvas.drawArc(4f,4f,20f,20f,35f,290f,false,paint); line(canvas,16f,3f,20f,7f,15f,8f) }
            PocketIcon.CHEVRON -> line(canvas,9f,5f,16f,12f,9f,19f)
            PocketIcon.BACK -> { line(canvas,10f,5f,3f,12f,10f,19f); line(canvas,3f,12f,21f,12f) }
            PocketIcon.CHECK -> line(canvas,4f,12f,9f,17f,20f,6f)
            PocketIcon.CLOSE -> { line(canvas,6f,6f,18f,18f); line(canvas,6f,18f,18f,6f) }
            PocketIcon.STOP -> canvas.drawRoundRect(5f,5f,19f,19f,2f,2f,paint)
            PocketIcon.LIST -> { for (y in listOf(6f,12f,18f)) { canvas.drawPoint(3f,y,paint); line(canvas,8f,y,21f,y) } }
            PocketIcon.SEARCH -> { canvas.drawCircle(10f,10f,7f,paint); line(canvas,15f,15f,22f,22f) }
            PocketIcon.LOG -> { canvas.drawRoundRect(4f,2f,20f,22f,2f,2f,paint); for (y in listOf(7f,12f,17f)) line(canvas,8f,y,16f,y) }
            PocketIcon.COPY -> { canvas.drawRoundRect(8f,7f,21f,22f,2f,2f,paint); line(canvas,16f,3f,3f,3f,3f,17f) }
            PocketIcon.SHARE -> { canvas.drawCircle(18f,4f,2.5f,paint); canvas.drawCircle(5f,12f,2.5f,paint); canvas.drawCircle(18f,20f,2.5f,paint); line(canvas,7f,10.5f,15.5f,5.5f); line(canvas,7f,13.5f,15.5f,18.5f) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class PocketButton(context: Context, icon: PocketIcon? = null,
    private val navigation: Boolean = false, private val prominent: Boolean = false,
    private val arrow: Boolean = false) : Button(context) {
    private val d = resources.displayMetrics.density
    private val symbol = PocketIconDrawable(icon ?: PocketIcon.INFO)
    private val chevron = PocketIconDrawable(PocketIcon.CHEVRON)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var iconKind: PocketIcon? = icon
        set(value) { if (field != value) { field = value; invalidate() } }
    init {
        isAllCaps = false; includeFontPadding = false
        textSize = if (navigation) 15f else 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = if (icon == null) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        minimumHeight = (d * if (navigation) 46 else 50).toInt(); minHeight = minimumHeight
        setPadding((d * if (icon == null) 14 else if (navigation) 46 else 76).toInt(), (d*10).toInt(), (d * if (arrow) 40 else 16).toInt(), (d*10).toInt())
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(PocketColors.disabled, if (prominent) PocketColors.background else PocketColors.ink)))
        background = PocketSurface(d, navigation = navigation, prominent = prominent)
        stateListAnimator = null; elevation = 0f
        defaultFocusHighlightEnabled = false
        isFocusable = true; isFocusableInTouchMode = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tint = if (!isEnabled) PocketColors.disabled else if (prominent) PocketColors.background else PocketColors.ink
        iconKind?.let {
            val size = if (navigation) 20 else 25
            val left = ((if (navigation) 14 else 21)*d).toInt(); val top = ((height-size*d)/2).toInt()
            symbol.kind = it; symbol.symbolColor = tint; symbol.alpha = if (isEnabled) 255 else 115
            symbol.setBounds(left,top,left+(size*d).toInt(),top+(size*d).toInt()); symbol.draw(canvas)
            if (!navigation) {
                paint.color = if (prominent) Color.argb(70,8,15,23) else Color.argb(65,151,196,207)
                paint.strokeWidth = d*.7f; canvas.drawLine(60*d,height/2f-11*d,60*d,height/2f+11*d,paint)
            }
        }
        if (arrow) {
            chevron.symbolColor = tint; chevron.alpha = if (isEnabled) 220 else 100
            val left = width-(31*d).toInt(); val top = height/2-(9*d).toInt()
            chevron.setBounds(left,top,left+(18*d).toInt(),top+(18*d).toInt()); chevron.draw(canvas)
        }
    }
}

internal class HeroEmblem(context: Context, initial: PocketIcon) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val symbol = PocketIconDrawable(initial, PocketColors.mint)
    var kind: PocketIcon = initial
        set(value) { if (field != value) { field = value; invalidate() } }
    var accent: Int = PocketColors.mint
        set(value) { if (field != value) { field = value; invalidate() } }
    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val side = minOf(width,height).toFloat(); val r = side*.35f
        val x = width*.5f; val y = height*.46f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x,y+r*.9f,r*1.5f,intArrayOf(Color.argb(65,Color.red(accent),Color.green(accent),Color.blue(accent)),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        canvas.drawOval(x-r*1.5f,y+r*.65f,x+r*1.5f,y+r*1.3f,paint)
        paint.shader = LinearGradient(x-r,y-r,x+r,y+r,intArrayOf(Color.rgb(243,251,255),Color.rgb(75,123,145),Color.rgb(12,38,53),accent,Color.rgb(220,246,250)),floatArrayOf(0f,.24f,.55f,.82f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,r,paint)
        paint.shader = RadialGradient(x-r*.35f,y-r*.45f,r*1.8f,intArrayOf(Color.rgb(66,116,131),Color.rgb(10,37,50),Color.rgb(14,97,106)),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,r*.94f,paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = side*.008f
        paint.color = Color.argb(145,218,250,255); canvas.drawCircle(x,y,r*.87f,paint)
        paint.color = Color.argb(210,239,254,255); paint.strokeWidth = side*.017f
        canvas.drawArc(x-r*.92f,y-r*.92f,x+r*.92f,y+r*.92f,204f,78f,false,paint)
        symbol.kind = kind; symbol.symbolColor = accent
        val half = (r*.53f).toInt(); symbol.setBounds(x.toInt()-half,y.toInt()-half,x.toInt()+half,y.toInt()+half)
        symbol.draw(canvas)
    }
}
