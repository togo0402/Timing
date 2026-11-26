package com.togotiming.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.ColorPalette
import com.togotiming.app.domain.TimeMath
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 三个小部件的 Canvas 渲染。
 *
 * 设计约束：
 * - RemoteViews 里只能放 TextView / ImageView / Chronometer / GridView 等白名单控件，
 *   圆角、渐变、圆环、磁贴全部在 App 进程画成 Bitmap，再 setImageViewBitmap 塞进去；
 * - 所有 Bitmap 像素数受 [WidgetDraw.sizeFor] 限制，避免 Binder 事务超限；
 * - W1 的秒表数字用真实 Chronometer 控件叠在 Bitmap 上（系统自己每秒跳，不靠 Service）。
 */
internal object WidgetRenderer {

    private const val BUCKET_MS = 60_000L

    // ---------------------------------------------------------------- W1

    /** W1 激活态的字体 / 圆环颜色：浅色卡片上用深字，深色卡片上用白字 */
    fun timerInk(eventColor: Int): Int {
        val top = WidgetDraw.lighten(eventColor, 0.20f)
        val bottom = WidgetDraw.darken(eventColor, 0.22f)
        return ColorPalette.contrastText(WidgetDraw.blend(top, bottom, 0.5f))
    }

    /**
     * W1 2×2：圆角渐变卡片 + 秒表式进度环（[targetMs] 为满环，圆头）。
     * 环中央的文字由布局里的 TextView / Chronometer 承载，这里只画底和环。
     *
     * @param targetMs 本次计时的目标时长（record.targetMinutes ?: type.targetMinutes ?: 60 分钟）；
     *                 已用超过目标时进度封顶 100%，不会出现负数 / 溢出。
     */
    fun renderTimerCard(
        scale: WidgetScale,
        eventColor: Int,
        elapsedMs: Long,
        targetMs: Long,
        active: Boolean,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val size = WidgetDraw.sizeFor(widthPx, heightPx)
        val bitmap = WidgetDraw.createBitmap(size)
        val canvas = Canvas(bitmap)

        val w = size.width.toFloat()
        val h = size.height.toFloat()
        fun dp(value: Float): Float = scale.dp(value) * size.scale

        val minSide = min(w, h)
        val radius = min(minSide * 0.24f, dp(30f))

        val top = if (active) WidgetDraw.lighten(eventColor, 0.20f) else WidgetDraw.CARD_TOP
        val bottom = if (active) WidgetDraw.darken(eventColor, 0.22f) else WidgetDraw.CARD_BOTTOM
        val ink = if (active) timerInk(eventColor) else WidgetDraw.TEXT_PRIMARY

        WidgetDraw.drawCard(
            canvas = canvas,
            width = w,
            height = h,
            radius = radius,
            topColor = top,
            bottomColor = bottom,
            borderColor = WidgetDraw.withAlpha(ink, if (active) 0.16f else 0.06f),
            borderWidth = dp(1f).coerceAtLeast(1f),
        )

        val cx = w / 2f
        val cy = h / 2f
        val ringRadius = minSide * 0.395f
        val stroke = minSide * 0.052f
        val ring = WidgetDraw.ringRect(cx, cy, ringRadius)

        // 底轨
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = if (active) WidgetDraw.withAlpha(ink, 0.20f) else WidgetDraw.TRACK
        }
        canvas.drawArc(ring, 0f, 360f, false, trackPaint)

        // 进度弧：目标时长为满环，圆头；超过目标保持满环，不出现负数 / 溢出
        val safeTarget = targetMs.coerceAtLeast(1L)
        val progress = if (active) {
            (elapsedMs.coerceAtLeast(0L).toFloat() / safeTarget.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (progress > 0f) {
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = WidgetDraw.withAlpha(ink, 0.94f)
            }
            canvas.drawArc(ring, -90f, 360f * progress.coerceAtLeast(0.004f), false, progressPaint)
        }

        // 环内极淡高光：玻璃质感 + 提亮中心，保证叠在上面的文字可读
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetDraw.withAlpha(0xFFFFFFFF.toInt(), if (active) 0.12f else 0.50f)
        }
        canvas.drawCircle(cx, cy, (ringRadius - stroke * 1.15f).coerceAtLeast(1f), gloss)

        return bitmap
    }

    // ---------------------------------------------------------------- W2

    /**
     * W2 2×2：白色圆角卡片 + 今日 24h 分布环。
     * - 底轨 = 未记录时间（#E8EDF5）；
     * - 已记录的每一段按叶子事件色画弧，**全部为平头（BUTT）**；
     * - 只有**正在进行的事件**在其前进端（now 对应位置）补一个同色的圆形圆头，
     *   所以每一段起点是平的、只有进行中的那一头是圆的；
     * - 不再绘制任何白色圆点标记；无文字。
     */
    fun renderDayRing(
        scale: WidgetScale,
        records: List<TimeRecordEntity>,
        types: List<ActivityTypeEntity>,
        from: Long,
        to: Long,
        now: Long,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val size = WidgetDraw.sizeFor(widthPx, heightPx)
        val bitmap = WidgetDraw.createBitmap(size)
        val canvas = Canvas(bitmap)

        val w = size.width.toFloat()
        val h = size.height.toFloat()
        fun dp(value: Float): Float = scale.dp(value) * size.scale

        val minSide = min(w, h)
        val cardRadius = min(minSide * 0.22f, dp(28f))
        WidgetDraw.drawCard(
            canvas = canvas,
            width = w,
            height = h,
            radius = cardRadius,
            topColor = WidgetDraw.CARD_TOP,
            bottomColor = WidgetDraw.CARD_BOTTOM,
            borderColor = WidgetDraw.withAlpha(WidgetDraw.TEXT_PRIMARY, 0.06f),
            borderWidth = dp(1f).coerceAtLeast(1f),
        )

        val cx = w / 2f
        val cy = h / 2f
        val ringRadius = minSide * 0.355f
        val stroke = minSide * 0.085f
        val ring = WidgetDraw.ringRect(cx, cy, ringRadius)

        // 24h 底轨 = 未记录时间
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = WidgetDraw.TRACK
        }
        canvas.drawArc(ring, 0f, 360f, false, trackPaint)

        val dayMs = (to - from).coerceAtLeast(BUCKET_MS)
        val bucketCount = ((dayMs + BUCKET_MS - 1) / BUCKET_MS).toInt().coerceAtLeast(1)
        val typeMap = types.associateBy { it.id }
        val owner = LongArray(bucketCount)

        // 先按时间顺序落桶：后开始的记录覆盖前面的（处理手动补记的重叠）
        records.sortedBy { it.startAt }.forEach { record ->
            val start = max(record.startAt, from)
            val end = min(record.endAt ?: now, to)
            if (end <= start) return@forEach
            val first = ((start - from) / BUCKET_MS).toInt().coerceIn(0, bucketCount - 1)
            val lastExclusive = (((end - from) + BUCKET_MS - 1) / BUCKET_MS)
                .toInt().coerceIn(1, bucketCount)
            for (index in first until lastExclusive) owner[index] = record.typeId
        }

        // 连续同类型的桶合成一段
        data class Segment(val startBucket: Int, val endBucket: Int, val typeId: Long)

        val segments = ArrayList<Segment>()
        var cursor = 0
        while (cursor < bucketCount) {
            val typeId = owner[cursor]
            if (typeId == 0L) {
                cursor++
                continue
            }
            var end = cursor + 1
            while (end < bucketCount && owner[end] == typeId) end++
            segments.add(Segment(cursor, end, typeId))
            cursor = end
        }

        // 段数 > 8：时长最短的类型并入灰色「其它」，主色段保留
        val bucketsByType = HashMap<Long, Int>()
        segments.forEach { seg ->
            bucketsByType[seg.typeId] = (bucketsByType[seg.typeId] ?: 0) + (seg.endBucket - seg.startBucket)
        }
        val highlighted = bucketsByType.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key }
            .toHashSet()

        val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }
        segments.forEach { seg ->
            val color = if (seg.typeId in highlighted) {
                typeMap[seg.typeId]?.colorArgb ?: WidgetDraw.OTHER_GRAY
            } else {
                WidgetDraw.OTHER_GRAY
            }
            var startAngle = -90f + 360f * seg.startBucket / bucketCount
            var sweep = 360f * (seg.endBucket - seg.startBucket) / bucketCount
            if (sweep > 1.4f) {
                // 留一丝缝隙，相邻事件颜色更易分辨
                startAngle += 0.45f
                sweep -= 0.9f
            }
            segmentPaint.color = color
            canvas.drawArc(ring, startAngle, sweep.coerceAtLeast(0.4f), false, segmentPaint)
        }

        // 只有「正在进行的事件」的前进端（now 位置）画一个与弧同色的圆头：
        // 弧段本身都是 BUTT 平头，因此只有这一头是圆的，且整环没有额外白色圆点。
        val activeRecord = records.firstOrNull { it.endAt == null }
        if (activeRecord != null) {
            val clampedNow = now.coerceIn(from, to)
            if (clampedNow > from) {
                val headFraction = ((clampedNow - from).toFloat() / dayMs.toFloat()).coerceIn(0f, 1f)
                val headAngle = Math.toRadians(-90.0 + 360.0 * headFraction)
                val headX = cx + (ringRadius * cos(headAngle)).toFloat()
                val headY = cy + (ringRadius * sin(headAngle)).toFloat()
                val nowBucket = (((clampedNow - from) / BUCKET_MS).toInt()).coerceIn(0, bucketCount - 1)
                // 只有 now 位置确实有弧段时才补圆头（取该弧段颜色，保证「圆头与弧同色」）
                val headTypeId = owner[nowBucket]
                if (headTypeId != 0L) {
                    val headColor = typeMap[headTypeId]?.colorArgb ?: WidgetDraw.OTHER_GRAY
                    canvas.drawCircle(
                        headX,
                        headY,
                        stroke / 2f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = headColor },
                    )
                }
            }
        }

        return bitmap
    }

    // ---------------------------------------------------------------- W3

    /**
     * W3 磁贴：彩色磁贴（事件色浅底 + 左侧色条 + 右侧百分比环），
     * 一块磁贴 = 事件名 + 今日累计 + 百分比环，整块 Canvas 画成 Bitmap。
     */
    fun renderTile(
        scale: WidgetScale,
        name: String,
        millis: Long,
        percent: Float,
        eventColor: Int,
        active: Boolean,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val size = WidgetDraw.sizeFor(widthPx, heightPx)
        val bitmap = WidgetDraw.createBitmap(size)
        val canvas = Canvas(bitmap)

        val w = size.width.toFloat()
        val h = size.height.toFloat()
        fun dp(value: Float): Float = scale.dp(value) * size.scale

        val minSide = min(w, h)
        val radius = min(minSide * 0.30f, dp(16f)).coerceAtLeast(dp(8f))

        // 磁贴底：事件色的浅色渐变，保持「彩色磁贴」辨识度
        WidgetDraw.drawCard(
            canvas = canvas,
            width = w,
            height = h,
            radius = radius,
            topColor = WidgetDraw.lighten(eventColor, 0.90f),
            bottomColor = WidgetDraw.lighten(eventColor, 0.84f),
            borderColor = WidgetDraw.withAlpha(eventColor, 0.20f),
            borderWidth = dp(1f).coerceAtLeast(1f),
        )

        // 左侧色条
        val barWidth = dp(3f).coerceAtLeast(1.5f)
        val barX = dp(9f)
        val barTop = max(dp(9f), h * 0.20f)
        val barBottom = h - barTop
        if (barBottom > barTop) {
            canvas.drawRoundRect(
                barX,
                barTop,
                barX + barWidth,
                barBottom,
                barWidth / 2f,
                barWidth / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eventColor },
            )
        }

        val contentLeft = barX + barWidth + dp(8f)
        val ringMargin = dp(9f)
        val minNameWidth = dp(34f)

        // 右侧百分比环（环本身代表占比，空间够时环心再写百分比数字）
        var ringRadius = min(h * 0.30f, w * 0.135f).coerceIn(dp(8f), dp(16f))
        var ringCx = w - ringMargin - ringRadius
        if (ringCx - ringRadius - dp(6f) - contentLeft < minNameWidth) {
            val allowed = ((w - ringMargin - dp(6f) - contentLeft - minNameWidth) / 2f)
            ringRadius = allowed.coerceIn(dp(7f), dp(16f))
            ringCx = w - ringMargin - ringRadius
        }
        val ringCy = h / 2f
        val ringStroke = (ringRadius * 0.26f).coerceAtLeast(dp(2.2f))
        val ringRect = WidgetDraw.ringRect(ringCx, ringCy, ringRadius)

        canvas.drawArc(
            ringRect,
            0f,
            360f,
            false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ringStroke
                color = WidgetDraw.TRACK
            },
        )
        // 百分比 clamp 到 0..100（NaN / 越界输入都归零处理），进度弧不超过一整圈
        val fraction = if (percent.isNaN()) 0f else percent.coerceIn(0f, 1f)
        if (fraction > 0f) {
            canvas.drawArc(
                ringRect,
                -90f,
                360f * fraction.coerceAtMost(1f),
                false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = ringStroke
                    strokeCap = Paint.Cap.ROUND
                    color = eventColor
                },
            )
        }

        val percentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scale.sp(8.5f) * size.scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = WidgetDraw.darken(eventColor, 0.50f)
        }
        val percentText = "${(fraction * 100f).roundToInt().coerceIn(0, 100)}%"
        val innerRadius = ringRadius - ringStroke * 0.5f
        if (innerRadius > dp(6f) && percentPaint.measureText(percentText) <= innerRadius * 1.85f) {
            val metrics = percentPaint.fontMetrics
            canvas.drawText(
                percentText,
                ringCx,
                ringCy - (metrics.ascent + metrics.descent) / 2f,
                percentPaint,
            )
        }

        // 字号随磁贴高度收缩，避免小尺寸磁贴里的文字竖向出界
        val nameSize = min(scale.sp(12.5f) * size.scale, h * 0.26f).coerceAtLeast(1f)
        val durationSize = min(scale.sp(10f) * size.scale, h * 0.20f).coerceAtLeast(1f)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = nameSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = WidgetDraw.TEXT_PRIMARY
        }
        val durationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = durationSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = WidgetDraw.darken(eventColor, 0.45f)
        }
        // 所有文字都限宽省略（Canvas 没有 maxLines，这里等价于单行 + ellipsize）
        val textRightLimit = ringCx - ringRadius - dp(7f)
        val nameMaxWidth = (textRightLimit - contentLeft).coerceAtLeast(dp(16f))
        val displayName = WidgetDraw.ellipsize(namePaint, name, nameMaxWidth)
        val durationMaxWidth = (textRightLimit - contentLeft - (if (active) dp(9f) else 0f))
            .coerceAtLeast(dp(12f))
        val durationText = WidgetDraw.ellipsize(
            durationPaint,
            TimeMath.formatDuration(millis),
            durationMaxWidth,
        )

        val nameMetrics = namePaint.fontMetrics
        val durationMetrics = durationPaint.fontMetrics
        val lineGap = dp(2f)
        val blockHeight = (nameMetrics.descent - nameMetrics.ascent) +
            lineGap +
            (durationMetrics.descent - durationMetrics.ascent)
        val blockTop = (h - blockHeight) / 2f
        val nameBaseline = blockTop - nameMetrics.ascent
        val durationBaseline = blockTop +
            (nameMetrics.descent - nameMetrics.ascent) +
            lineGap -
            durationMetrics.ascent

        canvas.drawText(displayName, contentLeft, nameBaseline, namePaint)
        canvas.drawText(durationText, contentLeft, durationBaseline, durationPaint)

        // 进行中的一级节点：时长后面加一个小圆点提示（放不下就不画，避免压到右侧环）
        if (active) {
            val dotRadius = dp(2f).coerceAtLeast(1f)
            val dotX = contentLeft + durationPaint.measureText(durationText) + dp(5f)
            if (dotX + dotRadius <= textRightLimit) {
                canvas.drawCircle(
                    dotX,
                    durationBaseline - dp(3.4f),
                    dotRadius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eventColor },
                )
            }
        }

        return bitmap
    }

    /** W3 整体底部卡片（圆角 + 浅色渐变），磁贴 / 标题叠在上面 */
    fun renderWidgetCard(scale: WidgetScale, widthPx: Int, heightPx: Int): Bitmap {
        val size = WidgetDraw.sizeFor(widthPx, heightPx)
        val bitmap = WidgetDraw.createBitmap(size)
        val canvas = Canvas(bitmap)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        fun dp(value: Float): Float = scale.dp(value) * size.scale
        val radius = min(min(w, h) * 0.18f, dp(26f))
        WidgetDraw.drawCard(
            canvas = canvas,
            width = w,
            height = h,
            radius = radius,
            topColor = WidgetDraw.CARD_TOP,
            bottomColor = WidgetDraw.CARD_BOTTOM,
            borderColor = WidgetDraw.withAlpha(WidgetDraw.TEXT_PRIMARY, 0.06f),
            borderWidth = dp(1f).coerceAtLeast(1f),
        )
        return bitmap
    }
}
