package com.togotiming.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.sqrt

/** 小部件绘制用的密度换算（dp / sp → px） */
internal class WidgetScale(context: Context) {
    private val metrics = context.resources.displayMetrics

    fun dp(value: Float): Float = value * metrics.density

    fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
}

/** 一个 Bitmap 的目标像素尺寸；[scale] 是「被压缩的比例」，所有固定 dp 尺寸需乘上它 */
internal data class BitmapSize(val width: Int, val height: Int, val scale: Float)

/**
 * 小部件 Canvas 绘制的公共工具。
 *
 * 注意：RemoteViews 里的 Bitmap 会随 Binder 事务传输（ARGB_8888 = 4B/px），
 * 单个 Bitmap 超过约 1MB 会触发 TransactionTooLargeException，
 * 因此这里统一把像素数限制在 [MAX_PIXELS]（约 860KB）以内。
 */
internal object WidgetDraw {

    // ---- 浅色玻璃卡片（v1.1 暂不做深色小部件） ----
    const val CARD_TOP = 0xFFF7FAFF.toInt()
    const val CARD_BOTTOM = 0xFFEDF3FC.toInt()

    /** 未记录时间 / 进度环底轨 */
    const val TRACK = 0xFFE8EDF5.toInt()

    const val TEXT_PRIMARY = 0xFF101828.toInt()
    const val TEXT_SECONDARY = 0xFF667085.toInt()
    const val TEXT_TERTIARY = 0xFF8B98AD.toInt()

    /** 品牌蓝（W1 空闲态、W2 当前时间点头） */
    const val BRAND = 0xFF3D8EFF.toInt()
    const val BRAND_DEEP = 0xFF2470D8.toInt()

    /** W2 段数超限时的「其它」灰 */
    const val OTHER_GRAY = 0xFFB0BEC5.toInt()

    private const val MAX_PIXELS = 220_000

    fun sizeFor(widthPx: Int, heightPx: Int): BitmapSize {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(64)
        val pixels = w.toLong() * h.toLong()
        if (pixels <= MAX_PIXELS) return BitmapSize(w, h, 1f)
        val k = sqrt(MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        return BitmapSize((w * k).toInt().coerceAtLeast(64), (h * k).toInt().coerceAtLeast(64), k)
    }

    fun createBitmap(size: BitmapSize): Bitmap =
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)

    /** 两个不透明颜色按比例插值（ratio = 0 取 from，1 取 to） */
    fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * r).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun lighten(color: Int, amount: Float): Int = blend(color, 0xFFFFFFFF.toInt(), amount)

    fun darken(color: Int, amount: Float): Int = blend(color, TEXT_PRIMARY, amount)

    /** 只改透明度（保留 RGB） */
    fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (color and 0x00FFFFFF)

    fun ringRect(cx: Float, cy: Float, radius: Float): RectF =
        RectF(cx - radius, cy - radius, cx + radius, cy + radius)

    /**
     * 圆角渐变卡片。
     * @param borderWidth 0 表示不描边
     */
    fun drawCard(
        canvas: Canvas,
        width: Float,
        height: Float,
        radius: Float,
        topColor: Int,
        bottomColor: Int,
        borderColor: Int = 0,
        borderWidth: Float = 0f,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, width, height), radius, radius, paint)
        paint.shader = null
        if (borderWidth > 0f && borderColor != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderWidth
            paint.color = borderColor
            val inset = borderWidth / 2f
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                radius,
                radius,
                paint,
            )
        }
    }

    /** 单行文本按可用宽度省略（END） */
    fun ellipsize(paint: TextPaint, text: String, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        return TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
    }
}
